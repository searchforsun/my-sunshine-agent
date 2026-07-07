package com.sunshine.rag.admin.config;

import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.admin.config.dto.ConfigBundleDraftView;
import com.sunshine.rag.admin.config.dto.ConfigVersionSummary;
import com.sunshine.rag.admin.config.dto.PublishBundleResult;
import com.sunshine.rag.admin.config.dto.SubmitEvalResult;
import com.sunshine.rag.admin.eval.dto.ConfigSuggestionItem;
import com.sunshine.rag.entity.RagConfigBundleEntity;
import com.sunshine.rag.entity.RagConfigVersionEntity;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigVersionService {

    private final RagConfigVersionRepository versionRepository;
    private final EvalJobRepository evalJobRepository;
    private final EvalReportRepository evalReportRepository;
    private final ConfigVersionStore store;
    private final ConfigVersionEvalLifecycle evalLifecycle;
    private final ConfigVersionPublishOps publishOps;

    @Transactional
    public void provisionBundleForNewKb(String tenantId, String kbId) {
        publishOps.provisionBundleForNewKb(tenantId, kbId);
    }

    @Transactional
    public RagConfigBundleEntity requireBundle(String tenantId, String kbId) {
        return store.requireBundle(tenantId, kbId);
    }

    public ConfigBundleDraftView getDraftView(String tenantId, String kbId) {
        RagConfigBundleEntity bundle = store.requireBundle(tenantId, kbId);
        if (bundle.getDraftVersionId() == null) {
            throw new ConfigVersionConflictException("当前无草稿，请从生效版本「复制为草稿」");
        }
        RagConfigVersionEntity working = store.requireVersion(bundle.getDraftVersionId());
        if (!ConfigVersionStatus.isDraft(working.getStatus())) {
            throw new ConfigVersionConflictException("当前无可用草稿，请从生效版本「复制为草稿」");
        }
        RagConfigVersionEntity active = bundle.getActivePublishedVersionId() != null
                ? versionRepository.findById(bundle.getActivePublishedVersionId()).orElse(null)
                : null;
        return new ConfigBundleDraftView(
                working.getId(),
                working.getVersionNo(),
                store.parsePayload(working),
                bundle.getActivePublishedVersionId(),
                active != null ? active.getVersionNo() : null);
    }

    public Map<String, Object> getEffective(String tenantId, String kbId, String mode, Long versionId) {
        RagConfigBundleEntity bundle = store.requireBundle(tenantId, kbId);
        RagConfigVersionEntity version = store.resolveVersion(bundle, mode, versionId);
        return store.parsePayload(version);
    }

    @Transactional
    public Map<String, Object> forkToDraft(String tenantId, String kbId, Long versionId) {
        RagConfigBundleEntity bundle = store.lockBundle(tenantId, kbId);
        evalLifecycle.ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity source = store.requireVersionInBundle(versionId, bundle);
        if (ConfigVersionStatus.isDraft(source.getStatus())) {
            return store.parsePayload(source);
        }
        if (ConfigVersionStatus.canRevertToDraft(source.getStatus())) {
            return revertToDraft(tenantId, kbId, versionId);
        }
        if (!ConfigVersionStatus.canCopyToDraft(source.getStatus())) {
            throw new IllegalArgumentException("当前状态不支持复制为草稿: " + source.getStatus());
        }
        if (store.findPipelineVersion(bundle.getId()).isPresent()) {
            throw new ConfigVersionConflictException("流水线已有进行中的配置版本，不可复制为草稿");
        }
        Map<String, Object> payload = store.parsePayload(source);
        int nextNo = store.nextVersionNo(bundle.getId());
        RagConfigVersionEntity newDraft = store.newVersion(bundle, nextNo, ConfigVersionStatus.DRAFT, payload, null);
        versionRepository.save(newDraft);
        bundle.setDraftVersionId(newDraft.getId());
        store.touchBundle(bundle);
        return payload;
    }

    @Transactional
    public Map<String, Object> revertToDraft(String tenantId, String kbId, Long versionId) {
        RagConfigBundleEntity bundle = store.lockBundle(tenantId, kbId);
        evalLifecycle.ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity target = store.requireVersionInBundle(versionId, bundle);
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
        store.touchBundle(bundle);
        log.info("[RAG] config reverted to draft tenant={} kb={} versionNo={}", tenantId, kbId, target.getVersionNo());
        return store.parsePayload(target);
    }

    @Transactional
    public Map<String, Object> saveDraft(
            String tenantId, String kbId, Map<String, Object> payload, String userId) {
        RagConfigBundleEntity bundle = store.lockBundle(tenantId, kbId);
        RagConfigVersionEntity draft = store.requireDraftPointer(bundle);
        if (!ConfigVersionStatus.isDraft(draft.getStatus())) {
            throw new ConfigVersionConflictException("仅草稿状态可编辑，当前状态: " + draft.getStatus());
        }
        draft.setPayloadJson(store.toJson(payload));
        if (userId != null && !userId.isBlank()) {
            draft.setCreatedBy(userId.strip());
        }
        versionRepository.save(draft);
        store.touchBundle(bundle);
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
        RagConfigBundleEntity bundle = store.lockBundle(tenantId, kbId);
        evalLifecycle.ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity target = store.requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.canApplySuggestions(target.getStatus())) {
            throw new ConfigVersionConflictException(
                    "仅评测失败状态可应用参数建议，当前: " + target.getStatus());
        }
        Map<String, Object> payload = store.deepCopy(store.parsePayload(target));
        for (ConfigSuggestionItem item : suggestions) {
            if (item.path() == null || item.path().isBlank() || item.proposed() == null) {
                continue;
            }
            ConfigBundlePathUtils.setPath(payload, item.path().strip(), item.proposed());
        }
        target.setPayloadJson(store.toJson(payload));
        target.setStatus(ConfigVersionStatus.DRAFT);
        target.setPublishedAt(null);
        versionRepository.save(target);
        bundle.setDraftVersionId(target.getId());
        store.touchBundle(bundle);
        log.info("[RAG] apply suggestions → draft tenant={} kb={} versionNo={} count={}",
                tenantId, kbId, target.getVersionNo(), suggestions.size());
        return payload;
    }

    @Transactional
    public SubmitEvalResult submitEval(String tenantId, String kbId) {
        return evalLifecycle.submitEval(tenantId, kbId);
    }

    @Transactional
    public void beginEvaluating(String tenantId, String kbId, Long versionId) {
        evalLifecycle.beginEvaluating(tenantId, kbId, versionId);
    }

    @Transactional
    public void completeEvalFromJob(String tenantId, String kbId, Long versionId, long jobId, boolean passed) {
        evalLifecycle.completeEvalFromJob(tenantId, kbId, versionId, jobId, passed);
    }

    @Transactional
    public void failEvalFromJob(String tenantId, String kbId, Long versionId, long jobId) {
        evalLifecycle.failEvalFromJob(tenantId, kbId, versionId, jobId);
    }

    @Transactional
    public PublishBundleResult activate(String tenantId, String kbId, Long versionId) {
        return publishOps.activate(tenantId, kbId, versionId);
    }

    public List<ConfigVersionSummary> listVersions(String tenantId, String kbId) {
        RagConfigBundleEntity bundle = store.requireBundle(tenantId, kbId);
        return versionRepository.findByBundleIdOrderByVersionNoDesc(bundle.getId()).stream()
                .map(version -> toSummary(version, bundle.getActivePublishedVersionId()))
                .toList();
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
                        : Optional.empty())
                .map(com.sunshine.rag.entity.EvalReportEntity::getRecallAt5)
                .orElse(null);
    }
}
