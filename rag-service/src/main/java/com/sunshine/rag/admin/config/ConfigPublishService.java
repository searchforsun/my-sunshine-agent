package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.config.dto.ConfigDraftSummary;
import com.sunshine.rag.admin.config.dto.NacosPublishResult;
import com.sunshine.rag.admin.config.dto.PublishDraftResult;
import com.sunshine.rag.admin.eval.EvaluateService;
import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;
import com.sunshine.rag.entity.EvalJobEntity;
import com.sunshine.rag.entity.EvalReportEntity;
import com.sunshine.rag.repository.ConfigDraftRepository;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConfigPublishService {

    private static final String STATUS_DRAFT = "draft";
    private static final String STATUS_PUBLISHED = "published";

    private final ConfigDraftService draftService;
    private final EvaluateService evaluateService;
    private final NacosPublishService nacosPublishService;
    private final ConfigDraftRepository draftRepository;
    private final EvalJobRepository evalJobRepository;
    private final EvalReportRepository evalReportRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public PublishDraftResult publishDraft(String tenantId, String scope, String kbId) {
        ConfigDraftSummary draft = draftService.getDraft(tenantId, scope)
                .orElseThrow(() -> new IllegalArgumentException("草稿不存在: " + scope));
        SmokeEvalResult evalResult = evaluateService.smokeEval(
                tenantId, kbId != null && !kbId.isBlank() ? kbId : "default", scope, draft.payload());
        if (!evalResult.passedGate()) {
            throw new PublishGateException(evalResult);
        }
        NacosPublishResult nacosResult = nacosPublishService.publish(scope, draft.payload());
        markDraftPublished(tenantId, scope);
        EvalReportEntity report = persistEvalReport(tenantId, kbId, draft.payload(), evalResult);
        return new PublishDraftResult(nacosResult, evalResult, report.getId());
    }

    private void markDraftPublished(String tenantId, String scope) {
        draftRepository.findFirstByTenantIdAndScopeAndStatusOrderByCreatedAtDesc(tenantId, scope, STATUS_DRAFT)
                .ifPresent(entity -> {
                    entity.setStatus(STATUS_PUBLISHED);
                    entity.setPublishedAt(Instant.now());
                    draftRepository.save(entity);
                });
    }

    private EvalReportEntity persistEvalReport(
            String tenantId, String kbId, Map<String, Object> payload, SmokeEvalResult evalResult) {
        EvalJobEntity job = new EvalJobEntity();
        job.setTenantId(tenantId);
        job.setKbId(kbId != null && !kbId.isBlank() ? kbId : "default");
        job.setSuite("smoke");
        job.setStatus("done");
        job.setFinishedAt(Instant.now());
        try {
            job.setConfigSnapshotJson(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            job.setConfigSnapshotJson("{}");
        }
        evalJobRepository.save(job);
        EvalReportEntity report = new EvalReportEntity();
        report.setJobId(job.getId());
        report.setRecallAt5(evalResult.recallAt5());
        report.setBaselineRecallAt5(evalResult.baselineRecallAt5());
        report.setPassedGate(true);
        evalReportRepository.save(report);
        job.setReportId(report.getId());
        evalJobRepository.save(job);
        return report;
    }
}
