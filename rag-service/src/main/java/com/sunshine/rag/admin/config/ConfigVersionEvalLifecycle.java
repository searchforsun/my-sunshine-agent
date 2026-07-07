package com.sunshine.rag.admin.config;

import com.sunshine.rag.admin.config.dto.SubmitEvalResult;
import com.sunshine.rag.entity.RagConfigBundleEntity;
import com.sunshine.rag.entity.RagConfigVersionEntity;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 配置版本评测状态机 */
@Slf4j
@Component
@RequiredArgsConstructor
class ConfigVersionEvalLifecycle {

    private final RagConfigVersionRepository versionRepository;
    private final ConfigVersionStore store;

    void ensureNoEvaluatingVersion(RagConfigBundleEntity bundle) {
        boolean evaluating = versionRepository.findByBundleIdAndStatus(bundle.getId(), ConfigVersionStatus.EVALUATING)
                .stream()
                .findAny()
                .isPresent();
        if (evaluating) {
            throw new ConfigVersionConflictException("存在评测中的版本，请等待评测完成后再操作");
        }
    }

    @Transactional
    SubmitEvalResult submitEval(String tenantId, String kbId) {
        RagConfigBundleEntity bundle = store.lockBundle(tenantId, kbId);
        ensureNoEvaluatingVersion(bundle);
        RagConfigVersionEntity draft = store.requireDraftPointer(bundle);
        if (!ConfigVersionStatus.isDraft(draft.getStatus())) {
            throw new ConfigVersionConflictException("仅草稿可提交评测，当前状态: " + draft.getStatus());
        }
        Optional<RagConfigVersionEntity> pipeline = store.findPipelineVersion(bundle.getId());
        if (pipeline.isPresent() && !pipeline.get().getId().equals(draft.getId())) {
            throw new ConfigVersionConflictException("流水线已有其他配置版本，不可提交评测");
        }
        draft.setStatus(ConfigVersionStatus.PENDING_EVAL);
        versionRepository.save(draft);
        store.touchBundle(bundle);
        log.info("[RAG] config submitted for eval tenant={} kb={} versionNo={}", tenantId, kbId, draft.getVersionNo());
        return new SubmitEvalResult(draft.getId(), draft.getVersionNo(), draft.getStatus());
    }

    @Transactional
    void beginEvaluating(String tenantId, String kbId, Long versionId) {
        if (versionId == null) {
            return;
        }
        RagConfigBundleEntity bundle = store.lockBundle(tenantId, kbId);
        RagConfigVersionEntity version = store.requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.canBeginEval(version.getStatus())) {
            return;
        }
        ensureNoEvaluatingVersion(bundle);
        version.setStatus(ConfigVersionStatus.EVALUATING);
        versionRepository.save(version);
        store.touchBundle(bundle);
        log.info("[RAG] config evaluating tenant={} kb={} versionNo={}", tenantId, kbId, version.getVersionNo());
    }

    @Transactional
    void completeEvalFromJob(String tenantId, String kbId, Long versionId, long jobId, boolean passed) {
        if (versionId == null) {
            return;
        }
        RagConfigBundleEntity bundle = store.lockBundle(tenantId, kbId);
        RagConfigVersionEntity version = store.requireVersionInBundle(versionId, bundle);
        if (!ConfigVersionStatus.isEvaluating(version.getStatus())) {
            log.warn("[RAG] skip completeEval versionId={} status={}", versionId, version.getStatus());
            return;
        }
        version.setPublishEvalJobId(jobId);
        if (passed) {
            version.setStatus(ConfigVersionStatus.EVAL_PASSED);
            version.setPublishedAt(store.uniqueInstant(bundle.getId(), java.time.Instant.now()));
        } else {
            version.setStatus(ConfigVersionStatus.EVAL_FAILED);
        }
        versionRepository.save(version);
        store.touchBundle(bundle);
        log.info("[RAG] config eval finished tenant={} kb={} versionNo={} passed={}",
                tenantId, kbId, version.getVersionNo(), passed);
    }

    @Transactional
    void failEvalFromJob(String tenantId, String kbId, Long versionId, long jobId) {
        completeEvalFromJob(tenantId, kbId, versionId, jobId, false);
    }
}
