package com.sunshine.rag.admin.config;

import com.sunshine.common.util.VersionTimestampDedup;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.admin.config.dto.ConfigBundleDraftView;
import com.sunshine.rag.admin.config.dto.ConfigVersionSummary;
import com.sunshine.rag.admin.config.dto.PublishBundleResult;
import com.sunshine.rag.admin.config.dto.SubmitEvalResult;
import com.sunshine.rag.admin.eval.dto.ConfigSuggestionItem;
import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;
import com.sunshine.rag.entity.EvalReportEntity;
import com.sunshine.rag.entity.RagConfigBundleEntity;
import com.sunshine.rag.entity.RagConfigVersionEntity;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import com.sunshine.rag.repository.KnowledgeBaseRepository;
import com.sunshine.rag.repository.RagConfigBundleRepository;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigVersionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final RagConfigBundleRepository bundleRepository;
    private final RagConfigVersionRepository versionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final EvalJobRepository evalJobRepository;
    private final EvalReportRepository evalReportRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public RagConfigBundleEntity requireBundle(String tenantId, String kbId) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        requireKb(tid, kid);
        return bundleRepository.findByTenantIdAndKbId(tid, kid)
                .orElseThrow(() -> new BizException(RagErrorCode.CONFIG_BUNDLE_NOT_FOUND));
    }

    /** 返回 bundle.draft_version_id 指向版本的 payload（流水线各态均可读，仅 draft 可写） */
    public ConfigBundleDraftView getDraftView(String tenantId, String kbId) {
        RagConfigBundleEntity bundle = requireBundle(tenantId, kbId);
        if (bundle.getDraftVersionId() == null) {
            throw new ConfigVersionConflictException("当前无草稿，请从生效版本「复制为草稿」");
        }
        RagConfigVersionEntity working = requireVersion(bundle.getDraftVersionId());
        if (!ConfigVersionStatus.isDraft(working.getStatus())) {
            throw new ConfigVersionConflictException("当前无可用草稿，请从生效版本「复制为草稿」");
        }
        RagConfigVersionEntity active = bundle.getActivePublishedVersionId() != null
                ? versionRepository.findById(bundle.getActivePublishedVersionId()).orElse(null)
                : null;
        return new ConfigBundleDraftView(
                working.getId(),
                working.getVersionNo(),
                parsePayload(working),
                bundle.getActivePublishedVersionId(),
                active != null ? active.getVersionNo() : null);
    }

    public Map<String, Object> getEffective(String tenantId, String kbId, String mode, Long versionId) {
        RagConfigBundleEntity bundle = requireBundle(tenantId, kbId);
        RagConfigVersionEntity version = resolveVersion(bundle, mode, versionId);
        return parsePayload(version);
    }

    /** 非 draft 版本复制 payload 到当前草稿行（active/superseded） */
    @Transactional
    public Map<String, Object> forkToDraft(String tenantId, String kbId, Long versionId) {
        RagConfigBundleEntity bundle = lockBundle(tenantId, kbId);
        ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity source = requireVersionInBundle(versionId, bundle);
        if (ConfigVersionStatus.isDraft(source.getStatus())) {
            return parsePayload(source);
        }
        if (ConfigVersionStatus.canRevertToDraft(source.getStatus())) {
            return revertToDraft(tenantId, kbId, versionId);
        }
        if (!ConfigVersionStatus.canCopyToDraft(source.getStatus())) {
            throw new IllegalArgumentException("当前状态不支持复制为草稿: " + source.getStatus());
        }
        if (findPipelineVersion(bundle.getId()).isPresent()) {
            throw new ConfigVersionConflictException("流水线已有进行中的配置版本，不可复制为草稿");
        }
        Map<String, Object> payload = parsePayload(source);
        int nextNo = nextVersionNo(bundle.getId());
        RagConfigVersionEntity newDraft = newVersion(bundle, nextNo, ConfigVersionStatus.DRAFT, payload, null);
        versionRepository.save(newDraft);
        bundle.setDraftVersionId(newDraft.getId());
        touchBundle(bundle);
        return payload;
    }

    /** 待评测/评测通过/评测失败 → 草稿（version_no 不变） */
    @Transactional
    public Map<String, Object> revertToDraft(String tenantId, String kbId, Long versionId) {
        RagConfigBundleEntity bundle = lockBundle(tenantId, kbId);
        ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity target = requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.canRevertToDraft(target.getStatus())) {
            throw new IllegalArgumentException("仅待评测/评测通过/评测失败版本可转为草稿");
        }
        if (ConfigVersionStatus.isActive(target.getStatus())) {
            throw new ConfigVersionConflictException("生效版本不可转为草稿");
        }
        target.setStatus(ConfigVersionStatus.DRAFT);
        target.setPublishedAt(null);
        versionRepository.save(target);
        bundle.setDraftVersionId(target.getId());
        touchBundle(bundle);
        log.info("[RAG] config reverted to draft tenant={} kb={} versionNo={}", tenantId, kbId, target.getVersionNo());
        return parsePayload(target);
    }

    @Transactional
    public Map<String, Object> saveDraft(
            String tenantId, String kbId, Map<String, Object> payload, String userId) {
        RagConfigBundleEntity bundle = lockBundle(tenantId, kbId);
        RagConfigVersionEntity draft = requireDraftPointer(bundle);
        if (!ConfigVersionStatus.isDraft(draft.getStatus())) {
            throw new ConfigVersionConflictException("仅草稿状态可编辑，当前状态: " + draft.getStatus());
        }
        draft.setPayloadJson(toJson(payload));
        if (userId != null && !userId.isBlank()) {
            draft.setCreatedBy(userId.strip());
        }
        versionRepository.save(draft);
        touchBundle(bundle);
        return payload;
    }

    @Transactional
    public Map<String, Object> applySuggestions(
            String tenantId, String kbId, List<ConfigSuggestionItem> suggestions, Long versionId) {
        if (suggestions == null || suggestions.isEmpty()) {
            if (versionId != null) {
                return getEffective(tenantId, kbId, "version", versionId);
            }
            return getDraftView(tenantId, kbId).payload();
        }
        if (versionId == null) {
            throw new ConfigVersionConflictException("须指定评测失败配置版本");
        }
        RagConfigBundleEntity bundle = lockBundle(tenantId, kbId);
        ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity target = requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.canApplySuggestions(target.getStatus())) {
            throw new ConfigVersionConflictException(
                    "仅评测失败状态可应用参数建议，当前: " + target.getStatus());
        }
        Map<String, Object> payload = deepCopy(parsePayload(target));
        for (ConfigSuggestionItem item : suggestions) {
            if (item.path() == null || item.path().isBlank() || item.proposed() == null) {
                continue;
            }
            ConfigBundlePathUtils.setPath(payload, item.path().strip(), item.proposed());
        }
        target.setPayloadJson(toJson(payload));
        target.setStatus(ConfigVersionStatus.DRAFT);
        target.setPublishedAt(null);
        versionRepository.save(target);
        bundle.setDraftVersionId(target.getId());
        touchBundle(bundle);
        log.info("[RAG] apply suggestions → draft tenant={} kb={} versionNo={} count={}",
                tenantId, kbId, target.getVersionNo(), suggestions.size());
        return payload;
    }

    /** 草稿 → 待评测（不执行评测，评测由评测页触发） */
    @Transactional
    public SubmitEvalResult submitEval(String tenantId, String kbId) {
        RagConfigBundleEntity bundle = lockBundle(tenantId, kbId);
        ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity draft = requireDraftPointer(bundle);
        if (!ConfigVersionStatus.isDraft(draft.getStatus())) {
            throw new ConfigVersionConflictException("仅草稿可提交评测，当前状态: " + draft.getStatus());
        }
        Optional<RagConfigVersionEntity> pipeline = findPipelineVersion(bundle.getId());
        if (pipeline.isPresent() && !pipeline.get().getId().equals(draft.getId())) {
            throw new ConfigVersionConflictException("流水线已有其他配置版本，不可提交评测");
        }
        draft.setStatus(ConfigVersionStatus.PENDING_EVAL);
        versionRepository.save(draft);
        touchBundle(bundle);
        log.info("[RAG] config submitted for eval tenant={} kb={} versionNo={}", tenantId, kbId, draft.getVersionNo());
        return new SubmitEvalResult(draft.getId(), draft.getVersionNo(), draft.getStatus());
    }

    /** 评测页触发：待评测 → 评测中 */
    @Transactional
    public void beginEvaluating(String tenantId, String kbId, Long versionId) {
        if (versionId == null) {
            return;
        }
        RagConfigBundleEntity bundle = lockBundle(tenantId, kbId);
        RagConfigVersionEntity version = requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.canBeginEval(version.getStatus())) {
            return;
        }
        ensureNoEvaluatingVersion(bundle);
        version.setStatus(ConfigVersionStatus.EVALUATING);
        versionRepository.save(version);
        touchBundle(bundle);
        log.info("[RAG] config evaluating tenant={} kb={} versionNo={}", tenantId, kbId, version.getVersionNo());
    }

    /** 评测完成：评测中 → 评测通过/失败 */
    @Transactional
    public void completeEvalFromJob(String tenantId, String kbId, Long versionId, long jobId, boolean passed) {
        if (versionId == null) {
            return;
        }
        RagConfigBundleEntity bundle = lockBundle(tenantId, kbId);
        RagConfigVersionEntity version = requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.isEvaluating(version.getStatus())) {
            log.warn("[RAG] skip completeEval versionId={} status={}", versionId, version.getStatus());
            return;
        }
        version.setPublishEvalJobId(jobId);
        if (passed) {
            version.setStatus(ConfigVersionStatus.EVAL_PASSED);
            version.setPublishedAt(uniqueInstant(bundle.getId(), Instant.now()));
        } else {
            version.setStatus(ConfigVersionStatus.EVAL_FAILED);
        }
        versionRepository.save(version);
        touchBundle(bundle);
        log.info("[RAG] config eval finished tenant={} kb={} versionNo={} passed={}",
                tenantId, kbId, version.getVersionNo(), passed);
    }

    /** 评测任务异常：评测中 → 评测失败 */
    @Transactional
    public void failEvalFromJob(String tenantId, String kbId, Long versionId, long jobId) {
        completeEvalFromJob(tenantId, kbId, versionId, jobId, false);
    }

    /** 仅最新 eval_passed 且 version_no 递增时可生效 */
    @Transactional
    public PublishBundleResult activate(String tenantId, String kbId, Long versionId) {
        RagConfigBundleEntity bundle = lockBundle(tenantId, kbId);
        ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity target = requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.EVAL_PASSED.equals(target.getStatus())) {
            throw new IllegalArgumentException("只能生效评测通过的版本");
        }
        RagConfigVersionEntity latestPassed = versionRepository
                .findFirstByBundleIdAndStatusOrderByVersionNoDesc(bundle.getId(), ConfigVersionStatus.EVAL_PASSED)
                .orElseThrow(() -> new IllegalStateException("无评测通过版本"));
        if (!latestPassed.getId().equals(target.getId())) {
            throw new ConfigVersionConflictException("仅支持生效最新评测通过版本 v" + latestPassed.getVersionNo());
        }
        int activeVersionNo = 0;
        if (bundle.getActivePublishedVersionId() != null) {
            RagConfigVersionEntity currentActive = requireVersion(bundle.getActivePublishedVersionId());
            activeVersionNo = currentActive.getVersionNo();
            if (target.getVersionNo() <= activeVersionNo) {
                throw new ConfigVersionConflictException(
                        "生效版本只能递增，当前生效 v" + activeVersionNo + "，无法生效 v" + target.getVersionNo());
            }
            supersedeActive(bundle);
        }
        target.setStatus(ConfigVersionStatus.ACTIVE);
        target.setPublishedAt(target.getPublishedAt() != null ? target.getPublishedAt() : uniqueInstant(bundle.getId(), Instant.now()));
        versionRepository.save(target);
        bundle.setActivePublishedVersionId(target.getId());
        ensureDraftAfterActivate(bundle, target);
        touchBundle(bundle);
        eventPublisher.publishEvent(new KbConfigChangedEvent(
                KnowledgeBaseService.normalizeTenant(tenantId),
                KnowledgeBaseService.requireKbId(kbId)));
        Double recall = targetRecallAt5(target);
        double recallVal = recall != null ? recall : 0.0;
        SmokeEvalResult eval = new SmokeEvalResult(recallVal, recallVal, true, List.of());
        log.info("[RAG] config activated tenant={} kb={} versionNo={}", tenantId, kbId, target.getVersionNo());
        return new PublishBundleResult(
                target.getId(),
                target.getVersionNo(),
                eval,
                target.getPublishEvalJobId() != null ? target.getPublishEvalJobId() : 0L);
    }

    public List<ConfigVersionSummary> listVersions(String tenantId, String kbId) {
        RagConfigBundleEntity bundle = requireBundle(tenantId, kbId);
        return versionRepository.findByBundleIdOrderByVersionNoDesc(bundle.getId()).stream()
                .map(version -> toSummary(version, bundle.getActivePublishedVersionId()))
                .toList();
    }

    private void ensureNoEvaluatingVersion(RagConfigBundleEntity bundle) {
        boolean evaluating = versionRepository.findByBundleIdAndStatus(bundle.getId(), ConfigVersionStatus.EVALUATING)
                .stream()
                .findAny()
                .isPresent();
        if (evaluating) {
            throw new ConfigVersionConflictException("存在评测中的版本，请等待评测完成后再操作");
        }
    }

    private Optional<RagConfigVersionEntity> findPipelineVersion(Long bundleId) {
        return versionRepository.findByBundleIdAndStatusIn(bundleId, List.of(
                        ConfigVersionStatus.DRAFT,
                        ConfigVersionStatus.PENDING_EVAL,
                        ConfigVersionStatus.EVALUATING,
                        ConfigVersionStatus.EVAL_PASSED,
                        ConfigVersionStatus.EVAL_FAILED))
                .stream()
                .findFirst();
    }

    private RagConfigBundleEntity lockBundle(String tenantId, String kbId) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        requireKb(tid, kid);
        return bundleRepository.findByTenantIdAndKbIdForUpdate(tid, kid)
                .orElseThrow(() -> new BizException(RagErrorCode.CONFIG_BUNDLE_NOT_FOUND));
    }

    private void ensureDraftAfterActivate(RagConfigBundleEntity bundle, RagConfigVersionEntity active) {
        if (active.getId().equals(bundle.getDraftVersionId())) {
            bundle.setDraftVersionId(null);
        }
    }

    private RagConfigVersionEntity findDraftVersion(RagConfigBundleEntity bundle) {
        if (bundle.getDraftVersionId() == null) {
            return null;
        }
        return versionRepository.findById(bundle.getDraftVersionId()).orElse(null);
    }

    private RagConfigVersionEntity requireDraftPointer(RagConfigBundleEntity bundle) {
        if (bundle.getDraftVersionId() == null) {
            throw new ConfigVersionConflictException("当前无草稿，请从生效版本「复制为草稿」");
        }
        RagConfigVersionEntity draft = requireVersion(bundle.getDraftVersionId());
        if (!ConfigVersionStatus.isDraft(draft.getStatus())) {
            throw new ConfigVersionConflictException("当前无可用草稿，请从生效版本「复制为草稿」");
        }
        return draft;
    }

    private RagConfigVersionEntity requireVersionInBundle(Long versionId, RagConfigBundleEntity bundle) {
        return versionRepository.findByIdAndBundleId(versionId, bundle.getId())
                .orElseThrow(() -> new IllegalArgumentException("版本不存在: " + versionId));
    }

    private RagConfigVersionEntity newVersion(
            RagConfigBundleEntity bundle,
            int versionNo,
            String status,
            Map<String, Object> payload,
            String createdBy) {
        RagConfigVersionEntity version = new RagConfigVersionEntity();
        version.setBundleId(bundle.getId());
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setPayloadJson(toJson(payload));
        version.setCreatedBy(createdBy);
        version.setCreatedAt(uniqueInstant(bundle.getId(), Instant.now()));
        return version;
    }

    /** bundle 内已有版本的 createdAt / publishedAt 秒级去重 */
    private Instant uniqueInstant(Long bundleId, Instant candidate) {
        List<Instant> existing = versionRepository.findByBundleIdOrderByVersionNoDesc(bundleId).stream()
                .flatMap(v -> java.util.stream.Stream.of(v.getCreatedAt(), v.getPublishedAt()))
                .filter(java.util.Objects::nonNull)
                .toList();
        return VersionTimestampDedup.uniqueInstant(candidate, existing);
    }

    private void supersedeActive(RagConfigBundleEntity bundle) {
        if (bundle.getActivePublishedVersionId() == null) {
            return;
        }
        versionRepository.findById(bundle.getActivePublishedVersionId()).ifPresent(active -> {
            if (ConfigVersionStatus.isActive(active.getStatus())) {
                active.setStatus(ConfigVersionStatus.SUPERSEDED);
                versionRepository.save(active);
            }
        });
    }

    private int nextVersionNo(Long bundleId) {
        return versionRepository.findFirstByBundleIdOrderByVersionNoDesc(bundleId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
    }

    private RagConfigVersionEntity resolveVersion(RagConfigBundleEntity bundle, String mode, Long versionId) {
        if (mode == null || mode.isBlank() || "published".equalsIgnoreCase(mode)) {
            return requireVersion(bundle.getActivePublishedVersionId());
        }
        if ("draft".equalsIgnoreCase(mode)) {
            RagConfigVersionEntity draft = requireDraftPointer(bundle);
            if (!ConfigVersionStatus.isDraft(draft.getStatus())) {
                throw new IllegalArgumentException("当前无可用草稿，draft 指针状态: " + draft.getStatus());
            }
            return draft;
        }
        if ("version".equalsIgnoreCase(mode)) {
            if (versionId == null) {
                throw new IllegalArgumentException("version 模式需要 versionId");
            }
            RagConfigVersionEntity version = requireVersionInBundle(versionId, bundle);
            if (ConfigVersionStatus.isDraft(version.getStatus())) {
                throw new IllegalArgumentException("草稿版本不可作为应用配置");
            }
            return version;
        }
        throw new IllegalArgumentException("未知 mode: " + mode);
    }

    private RagConfigVersionEntity requireVersion(Long versionId) {
        if (versionId == null) {
            throw new IllegalStateException("配置版本指针缺失");
        }
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("配置版本不存在: " + versionId));
    }

    private void touchBundle(RagConfigBundleEntity bundle) {
        bundle.setUpdatedAt(Instant.now());
        bundleRepository.save(bundle);
    }

    private ConfigVersionSummary toSummary(RagConfigVersionEntity version, Long activeVersionId) {
        return new ConfigVersionSummary(
                version.getId(),
                version.getVersionNo(),
                version.getStatus(),
                version.getCreatedAt(),
                version.getPublishedAt(),
                version.getId().equals(activeVersionId),
                targetRecallAt5(version),
                version.getChangeNote(),
                version.getCreatedBy());
    }

    private Double targetRecallAt5(RagConfigVersionEntity version) {
        if (version.getPublishEvalJobId() == null) {
            return null;
        }
        return evalJobRepository.findById(version.getPublishEvalJobId())
                .flatMap(job -> job.getReportId() != null
                        ? evalReportRepository.findById(job.getReportId())
                        : java.util.Optional.<EvalReportEntity>empty())
                .map(EvalReportEntity::getRecallAt5)
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(source), MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>(source);
        }
    }

    private Map<String, Object> parsePayload(RagConfigVersionEntity version) {
        try {
            return objectMapper.readValue(version.getPayloadJson(), MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("payload_json 无效: " + e.getMessage(), e);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("payload 序列化失败: " + e.getMessage(), e);
        }
    }

    private void requireKb(String tenantId, String kbId) {
        knowledgeBaseRepository.findByTenantIdAndKbId(tenantId, kbId)
                .orElseThrow(() -> new BizException(RagErrorCode.KB_NOT_FOUND));
    }
}
