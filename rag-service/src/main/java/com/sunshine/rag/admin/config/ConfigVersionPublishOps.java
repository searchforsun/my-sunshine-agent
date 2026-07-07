package com.sunshine.rag.admin.config;

import com.sunshine.rag.admin.config.dto.PublishBundleResult;
import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;
import com.sunshine.rag.entity.EvalReportEntity;
import com.sunshine.rag.entity.RagConfigBundleEntity;
import com.sunshine.rag.entity.RagConfigVersionEntity;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import com.sunshine.rag.repository.RagConfigBundleRepository;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 配置版本初始化与生效发布 */
@Slf4j
@Component
@RequiredArgsConstructor
class ConfigVersionPublishOps {

    private final RagConfigBundleRepository bundleRepository;
    private final RagConfigVersionRepository versionRepository;
    private final EvalJobRepository evalJobRepository;
    private final EvalReportRepository evalReportRepository;
    private final ConfigVersionStore store;
    private final ConfigVersionEvalLifecycle evalLifecycle;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    void provisionBundleForNewKb(String tenantId, String kbId) {
        String tid = com.sunshine.rag.admin.catalog.KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = com.sunshine.rag.admin.catalog.KnowledgeBaseService.requireKbId(kbId);
        if (bundleRepository.findByTenantIdAndKbId(tid, kid).isPresent()) {
            return;
        }
        Map<String, Object> payload = store.loadDefaultSeedPayload();
        RagConfigBundleEntity bundle = new RagConfigBundleEntity();
        bundle.setTenantId(tid);
        bundle.setKbId(kid);
        bundle.setCreatedAt(Instant.now());
        bundle.setUpdatedAt(Instant.now());
        bundleRepository.save(bundle);
        RagConfigVersionEntity v1 = store.newVersion(bundle, 1, ConfigVersionStatus.ACTIVE, payload, "kb-create-seed");
        v1.setPublishedAt(store.uniqueInstant(bundle.getId(), Instant.now()));
        versionRepository.save(v1);
        bundle.setActivePublishedVersionId(v1.getId());
        bundle.setDraftVersionId(null);
        store.touchBundle(bundle);
        log.info("[RAG] config bundle provisioned tenant={} kb={}", tid, kid);
    }

    @Transactional
    PublishBundleResult activate(String tenantId, String kbId, Long versionId) {
        RagConfigBundleEntity bundle = store.lockBundle(tenantId, kbId);
        evalLifecycle.ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity target = store.requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.EVAL_PASSED.equals(target.getStatus())) {
            throw new IllegalArgumentException("只能生效评测通过的版本");
        }
        RagConfigVersionEntity latestPassed = versionRepository
                .findFirstByBundleIdAndStatusOrderByVersionNoDesc(bundle.getId(), ConfigVersionStatus.EVAL_PASSED)
                .orElseThrow(() -> new IllegalStateException("无评测通过版本"));
        if (!latestPassed.getId().equals(target.getId())) {
            throw new ConfigVersionConflictException("仅支持生效最新评测通过版本 v" + latestPassed.getVersionNo());
        }
        if (bundle.getActivePublishedVersionId() != null) {
            RagConfigVersionEntity currentActive = store.requireVersion(bundle.getActivePublishedVersionId());
            if (target.getVersionNo() <= currentActive.getVersionNo()) {
                throw new ConfigVersionConflictException(
                        "生效版本只能递增，当前生效 v" + currentActive.getVersionNo() + "，无法生效 v" + target.getVersionNo());
            }
            store.supersedeActive(bundle);
        }
        target.setStatus(ConfigVersionStatus.ACTIVE);
        target.setPublishedAt(target.getPublishedAt() != null ? target.getPublishedAt() : store.uniqueInstant(bundle.getId(), Instant.now()));
        versionRepository.save(target);
        bundle.setActivePublishedVersionId(target.getId());
        store.ensureDraftAfterActivate(bundle, target);
        store.touchBundle(bundle);
        eventPublisher.publishEvent(new KbConfigChangedEvent(
                com.sunshine.rag.admin.catalog.KnowledgeBaseService.normalizeTenant(tenantId),
                com.sunshine.rag.admin.catalog.KnowledgeBaseService.requireKbId(kbId)));
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
}
