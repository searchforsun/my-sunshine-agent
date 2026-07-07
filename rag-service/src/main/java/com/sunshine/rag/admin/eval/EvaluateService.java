package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.admin.config.ConfigResolveMode;
import com.sunshine.rag.admin.config.ConfigVersionConflictException;
import com.sunshine.rag.admin.config.ConfigVersionService;
import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.admin.eval.dto.EvalJobSummary;
import com.sunshine.rag.admin.eval.dto.EvalJobStatus;
import com.sunshine.rag.admin.eval.dto.EvalReportView;
import com.sunshine.rag.admin.eval.dto.EvalRunRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuggestResult;
import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;
import com.sunshine.rag.entity.EvalJobEntity;
import com.sunshine.rag.entity.EvalReportEntity;
import com.sunshine.rag.entity.EvalSuiteEntity;
import com.sunshine.rag.entity.RagConfigVersionEntity;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import com.sunshine.rag.repository.EvalSuiteRepository;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluateService {

    private final EffectiveConfigResolver effectiveConfigResolver;
    private final EvalSuiteService evalSuiteService;
    private final PythonEvalRunner pythonEvalRunner;
    private final EvalReportRepository evalReportRepository;
    private final EvalJobRepository evalJobRepository;
    private final EvalSuiteRepository evalSuiteRepository;
    private final RagConfigVersionRepository configVersionRepository;
    private final EvalReportWriter evalReportWriter;
    private final ObjectMapper objectMapper;
    private final EvalAsyncRunner evalAsyncRunner;
    private final ObjectProvider<ConfigVersionService> configVersionServiceProvider;
    private final EvalSmokeRunner smokeRunner;
    private final EvalFullRunOrchestrator fullRunOrchestrator;
    private final EvalReportPersister reportPersister;

    /** 整包 draft payload smoke（ConfigVersionService publish/activate 用） */
    public SmokeEvalResult smokeEvalBundle(String tenantId, String kbId, Map<String, Object> bundlePayload) {
        return smokeRunner.smokeEvalBundle(tenantId, kbId, bundlePayload);
    }

    @Transactional
    public EvalJobStatus submitRun(String tenantId, EvalRunRequest request) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kbId = request.kbId() != null && !request.kbId().isBlank() ? request.kbId().strip() : "default";
        String suiteKey = request.suiteKey() != null && !request.suiteKey().isBlank()
                ? EvalSuiteKeys.normalizeSuiteKey(request.suiteKey().strip())
                : EvalSuiteKeys.DEFAULT_SUITE;
        EvalSuiteEntity suiteEntity = evalSuiteService.requireSuite(tid, suiteKey);
        ConfigResolveMode mode = ConfigResolveMode.parse(
                request.configMode() != null ? request.configMode() : "published");
        assertNoConcurrentEval(tid, kbId, request.configVersionId());
        EvalJobEntity job = new EvalJobEntity();
        job.setTenantId(tid);
        job.setKbId(kbId);
        job.setSuite(suiteKey);
        job.setSuiteId(suiteEntity.getId());
        job.setConfigMode(mode.name().toLowerCase());
        job.setConfigVersionId(request.configVersionId());
        job.setStatus("pending");
        job.setConfigSnapshotJson(writeSnapshot(request, kbId, suiteKey));
        evalJobRepository.save(job);
        long jobId = job.getId();
        if (request.configVersionId() != null) {
            configVersionServiceProvider.getObject().beginEvaluating(tid, kbId, request.configVersionId());
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evalAsyncRunner.runJob(jobId);
                }
            });
        } else {
            evalAsyncRunner.runJob(jobId);
        }
        return toJobStatus(job);
    }

    public EvalJobStatus getJob(long jobId) {
        return evalJobRepository.findById(jobId)
                .map(this::toJobStatus)
                .orElseThrow(() -> new IllegalArgumentException("eval job 不存在: " + jobId));
    }

    public EvalReportView getReport(long reportId) {
        EvalReportEntity report = evalReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("eval report 不存在: " + reportId));
        Map<String, Object> summary = reportPersister.parseJsonMap(report.getDeltaJson());
        if (report.getSummaryJson() != null && !report.getSummaryJson().isBlank()) {
            summary = reportPersister.parseJsonMap(report.getSummaryJson());
        }
        String jsonPath = summary.get("report_json_path") != null
                ? String.valueOf(summary.get("report_json_path"))
                : null;
        return new EvalReportView(
                report.getId(),
                report.getJobId(),
                report.getRecallAt5(),
                report.getMrr(),
                report.getPassedGate(),
                report.getBaselineRecallAt5(),
                summary,
                reportPersister.parseFailedSamples(report),
                reportPersister.parseSuggestions(report),
                report.getReportMdPath(),
                jsonPath);
    }

    public List<EvalJobSummary> listJobs(String tenantId, String kbId, int limit) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        int cap = limit > 0 ? Math.min(limit, 50) : 20;
        return evalJobRepository.findByTenantIdAndKbIdOrderByCreatedAtDesc(tid, kid).stream()
                .limit(cap)
                .map(this::toJobSummary)
                .toList();
    }

    public EffectiveRagConfig resolveEvalConfig(
            String tenantId, String kbId, ConfigResolveMode mode, Long versionId) {
        return effectiveConfigResolver.resolve(tenantId, kbId, mode, versionId).retrieval();
    }

    public void executeJob(long jobId) {
        EvalJobEntity job = evalJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("eval job 不存在: " + jobId));
        if (!"pending".equals(job.getStatus()) && !"running".equals(job.getStatus())) {
            return;
        }
        if ("running".equals(job.getStatus()) && job.getProcessedItems() > 0) {
            log.warn("[RAG] eval job {} 中断恢复，从 0 重新跑（已处理 {}/{}）",
                    jobId, job.getProcessedItems(), job.getTotalItems());
            job.setProcessedItems(0);
        }
        job.setStatus("running");
        evalJobRepository.save(job);
        try {
            Map<String, Object> snapshot = reportPersister.parseJsonMap(job.getConfigSnapshotJson());
            String strategy = stringVal(snapshot.get("strategy"));
            String kbId = stringVal(snapshot.get("kbId"));
            if (kbId.isBlank()) {
                kbId = job.getKbId();
            }
            ConfigResolveMode mode = ConfigResolveMode.parse(stringVal(snapshot.get("configMode")));
            Long versionId = snapshot.get("configVersionId") instanceof Number number ? number.longValue() : null;
            String suiteKey = stringVal(snapshot.get("suiteKey"));
            if (suiteKey.isBlank()) {
                suiteKey = EvalSuiteKeys.DEFAULT_SUITE;
            }
            EvalSuiteEntity suiteEntity = evalSuiteService.requireSuite(job.getTenantId(), suiteKey);
            Map<String, Object> report;
            if ("python".equals(suiteEntity.getKind())) {
                report = pythonEvalRunner.run(suiteEntity, job.getTenantId(), kbId, job.getId());
                report.put("suite_key", suiteKey);
            } else {
                report = fullRunOrchestrator.runFullEval(
                        job.getTenantId(), kbId, strategy, mode, versionId, suiteKey, job);
            }
            EvalReportWriter.WrittenReport written = evalReportWriter.write(report, job.getTenantId(), job.getId());
            reportPersister.persistFullReport(job, report, written);
            job.setReportObjectKey(written.jsonObjectKey());
            job.setStatus("done");
            job.setFinishedAt(Instant.now());
            evalJobRepository.save(job);
            finishConfigVersionEval(job, true);
        } catch (Exception e) {
            log.error("[RAG] eval job {} failed", jobId, e);
            job.setStatus("failed");
            job.setFinishedAt(Instant.now());
            evalJobRepository.save(job);
            finishConfigVersionEval(job, false);
        }
    }

    Map<String, Object> runFullEval(
            String tenantId, String kbId, String strategyOverride,
            ConfigResolveMode mode, Long versionId, String suiteKey) {
        return fullRunOrchestrator.runFullEval(tenantId, kbId, strategyOverride, mode, versionId, suiteKey);
    }

    private void finishConfigVersionEval(EvalJobEntity job, boolean success) {
        if (job.getConfigVersionId() == null) {
            return;
        }
        ConfigVersionService configVersionService = configVersionServiceProvider.getObject();
        if (success && job.getReportId() != null) {
            EvalReportEntity report = evalReportRepository.findById(job.getReportId()).orElse(null);
            boolean passed = report != null && Boolean.TRUE.equals(report.getPassedGate());
            configVersionService.completeEvalFromJob(
                    job.getTenantId(), job.getKbId(), job.getConfigVersionId(), job.getId(), passed);
        } else {
            configVersionService.failEvalFromJob(
                    job.getTenantId(), job.getKbId(), job.getConfigVersionId(), job.getId());
        }
    }

    private EvalJobSummary toJobSummary(EvalJobEntity job) {
        Double recallAt5 = null;
        Boolean passedGate = null;
        if (job.getReportId() != null) {
            EvalReportEntity report = evalReportRepository.findById(job.getReportId()).orElse(null);
            if (report != null) {
                recallAt5 = report.getRecallAt5();
                passedGate = report.getPassedGate();
            }
        }
        String suiteKey = resolveSuiteKey(job.getSuiteId());
        return new EvalJobSummary(
                job.getId(),
                job.getKbId(),
                job.getSuite(),
                suiteKey,
                job.getStatus(),
                job.getConfigVersionId(),
                resolveConfigVersionNo(job.getConfigVersionId()),
                job.getReportId(),
                recallAt5,
                passedGate,
                job.getCreatedAt(),
                job.getFinishedAt());
    }

    private Integer resolveConfigVersionNo(Long configVersionId) {
        if (configVersionId == null) {
            return null;
        }
        return configVersionRepository.findById(configVersionId)
                .map(RagConfigVersionEntity::getVersionNo)
                .orElse(null);
    }

    private void assertNoConcurrentEval(String tenantId, String kbId, Long configVersionId) {
        List<EvalJobEntity> active = evalJobRepository.findByTenantIdAndKbIdAndStatusIn(
                tenantId, kbId, List.of("pending", "running"));
        for (EvalJobEntity job : active) {
            if (matchesEvalConfig(job, configVersionId)) {
                throw new ConfigVersionConflictException("当前配置正在评测中，请稍后");
            }
        }
    }

    private static boolean matchesEvalConfig(EvalJobEntity job, Long configVersionId) {
        if (configVersionId != null) {
            return configVersionId.equals(job.getConfigVersionId());
        }
        return job.getConfigVersionId() == null;
    }

    private String resolveSuiteKey(Long suiteId) {
        if (suiteId == null) {
            return EvalSuiteKeys.DEFAULT_SUITE;
        }
        return evalSuiteRepository.findById(suiteId)
                .map(EvalSuiteEntity::getSuiteKey)
                .orElse(EvalSuiteKeys.DEFAULT_SUITE);
    }

    private EvalJobStatus toJobStatus(EvalJobEntity job) {
        Double progressPct = null;
        if (job.getTotalItems() != null && job.getTotalItems() > 0) {
            progressPct = Math.min(100.0, job.getProcessedItems() * 100.0 / job.getTotalItems());
        } else if ("done".equals(job.getStatus())) {
            progressPct = 100.0;
        } else if ("running".equals(job.getStatus())) {
            progressPct = 5.0;
        }
        return new EvalJobStatus(
                job.getId(),
                job.getTenantId(),
                job.getKbId(),
                job.getSuite(),
                job.getStatus(),
                job.getReportId(),
                job.getConfigVersionId(),
                job.getTotalItems(),
                job.getProcessedItems(),
                progressPct,
                job.getCreatedAt(),
                job.getFinishedAt());
    }

    private String writeSnapshot(EvalRunRequest request, String kbId, String suiteKey) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("suiteKey", suiteKey);
        snapshot.put("kbId", kbId);
        snapshot.put("strategy", request.strategy());
        snapshot.put("configMode", request.configMode() != null ? request.configMode() : "published");
        if (request.configVersionId() != null) {
            snapshot.put("configVersionId", request.configVersionId());
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String stringVal(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
