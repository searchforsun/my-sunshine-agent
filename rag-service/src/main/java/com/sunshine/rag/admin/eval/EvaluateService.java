package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.admin.config.ConfigBundlePayload;
import com.sunshine.rag.admin.config.ConfigDraftMerger;
import com.sunshine.rag.admin.config.ConfigVersionConflictException;
import com.sunshine.rag.admin.config.ConfigVersionService;
import com.sunshine.rag.admin.config.ConfigResolveMode;
import com.sunshine.rag.admin.config.ConfigScope;
import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.admin.config.ResolvedKbConfig;
import com.sunshine.rag.admin.eval.dto.EvalJobSummary;
import com.sunshine.rag.admin.eval.dto.EvalJobStatus;
import com.sunshine.rag.admin.eval.dto.EvalReportView;
import com.sunshine.rag.admin.eval.dto.EvalRunRequest;
import com.sunshine.rag.admin.eval.dto.EvalSuggestResult;
import com.sunshine.rag.admin.eval.dto.FailedEvalSample;
import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;
import com.sunshine.rag.config.RagEvalProperties;
import com.sunshine.rag.entity.EvalJobEntity;
import com.sunshine.rag.entity.EvalReportEntity;
import com.sunshine.rag.entity.EvalSuiteEntity;
import com.sunshine.rag.pipeline.KnowledgeRetrievalPipeline;
import com.sunshine.rag.pipeline.PipelineSearchRequest;
import com.sunshine.rag.pipeline.PipelineSearchResult;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.repository.EvalReportRepository;
import com.sunshine.rag.repository.EvalSuiteRepository;
import com.sunshine.rag.entity.RagConfigVersionEntity;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import com.sunshine.rag.service.RetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class EvaluateService {

    private static final DateTimeFormatter RUN_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter RUN_TAG = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KnowledgeRetrievalPipeline pipeline;
    private final EffectiveConfigResolver effectiveConfigResolver;
    private final GoldenSetLoader goldenSetLoader;
    private final EvalSuiteService evalSuiteService;
    private final PythonEvalRunner pythonEvalRunner;
    private final EvalReportRepository evalReportRepository;
    private final EvalJobRepository evalJobRepository;
    private final EvalSuiteRepository evalSuiteRepository;
    private final RagConfigVersionRepository configVersionRepository;
    private final EvalReportWriter evalReportWriter;
    private final RagEvalProperties evalProperties;
    private final ObjectMapper objectMapper;
    private final EvalAsyncRunner evalAsyncRunner;
    private final ObjectProvider<ConfigVersionService> configVersionServiceProvider;

    public EvaluateService(
            @Lazy KnowledgeRetrievalPipeline pipeline,
            EffectiveConfigResolver effectiveConfigResolver,
            GoldenSetLoader goldenSetLoader,
            EvalSuiteService evalSuiteService,
            PythonEvalRunner pythonEvalRunner,
            EvalReportRepository evalReportRepository,
            EvalJobRepository evalJobRepository,
            EvalSuiteRepository evalSuiteRepository,
            RagConfigVersionRepository configVersionRepository,
            EvalReportWriter evalReportWriter,
            RagEvalProperties evalProperties,
            ObjectMapper objectMapper,
            @Lazy EvalAsyncRunner evalAsyncRunner,
            ObjectProvider<ConfigVersionService> configVersionServiceProvider) {
        this.pipeline = pipeline;
        this.effectiveConfigResolver = effectiveConfigResolver;
        this.goldenSetLoader = goldenSetLoader;
        this.evalSuiteService = evalSuiteService;
        this.pythonEvalRunner = pythonEvalRunner;
        this.evalReportRepository = evalReportRepository;
        this.evalJobRepository = evalJobRepository;
        this.evalSuiteRepository = evalSuiteRepository;
        this.configVersionRepository = configVersionRepository;
        this.evalReportWriter = evalReportWriter;
        this.evalProperties = evalProperties;
        this.objectMapper = objectMapper;
        this.evalAsyncRunner = evalAsyncRunner;
        this.configVersionServiceProvider = configVersionServiceProvider;
    }

    /** 整包 draft payload smoke（ConfigVersionService publish/activate 用） */
    public SmokeEvalResult smokeEvalBundle(String tenantId, String kbId, Map<String, Object> bundlePayload) {
        ResolvedKbConfig resolved = effectiveConfigResolver.resolvePayload(tenantId, kbId, bundlePayload);
        EffectiveRagConfig config = resolved.retrieval();
        Map<String, String> id2name = goldenSetLoader.docIdToDisplayName(tenantId, kbId);
        List<GoldenSetLoader.GoldenQuery> queries = goldenSetLoader.smokeQueries(tenantId);
        double baseline = resolveBaselineRecallAt5();
        List<Double> recalls = new ArrayList<>();
        List<FailedEvalSample> failedSamples = new ArrayList<>();
        for (GoldenSetLoader.GoldenQuery item : queries) {
            Set<String> relevant = resolveRelevantNames(item, id2name);
            if (relevant.isEmpty()) {
                continue;
            }
            List<RetrievalService.DocFragment> hits = searchHits(
                    item.query(), 5, tenantId, kbId, null, true, config);
            double recall = EvalMetrics.recallAtK(hits, relevant, 5, config.minScore());
            recalls.add(recall);
            if (recall < 1.0) {
                failedSamples.add(new FailedEvalSample(
                        item.id(),
                        item.query(),
                        List.copyOf(relevant),
                        hits.stream().map(RetrievalService.DocFragment::docName).limit(5).toList()));
            }
        }
        double recallAt5 = average(recalls);
        boolean passedGate = recallAt5 >= baseline;
        log.info("[RAG] smoke eval bundle kb={} recall@5={} baseline={} passed={}",
                kbId, recallAt5, baseline, passedGate);
        return new SmokeEvalResult(recallAt5, baseline, passedGate, failedSamples);
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
        // 异步任务须在事务提交后启动，否则 executeJob 读不到刚插入的 job
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
        Map<String, Object> summary = parseJsonMap(report.getDeltaJson());
        if (report.getSummaryJson() != null && !report.getSummaryJson().isBlank()) {
            summary = parseJsonMap(report.getSummaryJson());
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
                parseFailedSamples(report),
                parseSuggestions(report),
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseFailedSamples(EvalReportEntity report) {
        Map<String, List<Map<String, Object>>> badcases = EvalSuggestContextBuilder.loadBadcases(
                report, parseJsonMap(report.getDeltaJson()), objectMapper);
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> row : badcases.getOrDefault("positive_miss", List.of())) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.putIfAbsent("sampleType", "positive_miss");
            merged.add(copy);
        }
        for (Map<String, Object> row : badcases.getOrDefault("negative_false_positive", List.of())) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.put("sampleType", "negative_false_positive");
            copy.putIfAbsent("expected", "（负例，应空召回）");
            merged.add(copy);
        }
        if (!merged.isEmpty()) {
            return merged;
        }
        if (report.getFailedSamplesJson() != null && !report.getFailedSamplesJson().isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(report.getFailedSamplesJson(), MAP_TYPE);
                Object failed = parsed.get("failedSamples");
                if (failed instanceof List<?> list) {
                    return (List<Map<String, Object>>) list;
                }
            } catch (Exception ignored) {
                // 回退 delta_json
            }
        }
        return List.of();
    }

    private EvalSuggestResult parseSuggestions(EvalReportEntity report) {
        if (report.getSuggestionsJson() == null || report.getSuggestionsJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(report.getSuggestionsJson(), EvalSuggestResult.class);
        } catch (Exception e) {
            return null;
        }
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
            Map<String, Object> snapshot = parseJsonMap(job.getConfigSnapshotJson());
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
                report = runFullEval(
                        job.getTenantId(), kbId, strategy, mode, versionId, suiteKey, job);
            }
            EvalReportWriter.WrittenReport written = evalReportWriter.write(report, job.getTenantId(), job.getId());
            persistFullReport(job, report, written);
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

    Map<String, Object> runFullEval(
            String tenantId, String kbId, String strategyOverride,
            ConfigResolveMode mode, Long versionId, String suiteKey) {
        return runFullEval(tenantId, kbId, strategyOverride, mode, versionId, suiteKey, null);
    }

    Map<String, Object> runFullEval(
            String tenantId, String kbId, String strategyOverride,
            ConfigResolveMode mode, Long versionId, String suiteKey, EvalJobEntity progressJob) {
        GoldenSetLoader.GoldenSetData golden = goldenSetLoader.load(tenantId, suiteKey);
        GoldenSetLoader.EvalSettings eval = golden.eval();
        List<GoldenSetLoader.GoldenQuery> queries = golden.queries();
        if (progressJob != null) {
            progressJob.setTotalItems(queries.size());
            progressJob.setProcessedItems(0);
            evalJobRepository.save(progressJob);
        }
        Map<String, String> id2name = goldenSetLoader.docIdToDisplayName(tenantId, kbId);
        EffectiveRagConfig config = effectiveConfigResolver.resolve(tenantId, kbId, mode, versionId).retrieval();
        List<Integer> topKs = eval.topK();
        int maxK = topKs.stream().mapToInt(Integer::intValue).max().orElse(5);
        Map<Integer, List<Double>> recalls = new LinkedHashMap<>();
        for (int k : topKs) {
            recalls.put(k, new ArrayList<>());
        }
        List<Double> mrrs = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();
        int posEmpty = 0;
        int posTotal = 0;
        int negEmpty = 0;
        int negTotal = 0;
        Map<String, List<Double>> byCategory = new LinkedHashMap<>();
        List<Map<String, Object>> positiveMiss = new ArrayList<>();
        List<Map<String, Object>> negativeFp = new ArrayList<>();
        int idx = 0;
        for (GoldenSetLoader.GoldenQuery query : queries) {
            idx++;
            long start = System.nanoTime();
            List<RetrievalService.DocFragment> hits = searchHits(
                    query.query(), maxK, tenantId, kbId, strategyOverride, true, config);
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            latencies.add(latencyMs);
            List<RetrievalService.DocFragment> filtered = EvalMetrics.filterByMinScore(hits, eval.minScore());
            if (query.positive()) {
                Set<String> relevant = resolveRelevantNames(query, id2name);
                posTotal++;
                if (filtered.isEmpty()) {
                    posEmpty++;
                }
                double r3 = EvalMetrics.recallAtK(filtered, relevant, 3, eval.minScore());
                for (int k : topKs) {
                    recalls.get(k).add(EvalMetrics.recallAtK(filtered, relevant, k, eval.minScore()));
                }
                double mrrVal = EvalMetrics.mrr(filtered, relevant, eval.minScore());
                mrrs.add(mrrVal);
                byCategory.computeIfAbsent(query.category(), key -> new ArrayList<>()).add(r3);
                if (r3 < 1.0) {
                    positiveMiss.add(buildPositiveMiss(query, relevant, filtered, eval.minScore()));
                }
            } else {
                negTotal++;
                if (filtered.isEmpty()) {
                    negEmpty++;
                } else {
                    negativeFp.add(buildNegativeFp(query, filtered));
                }
            }
            if (progressJob != null && (idx % 3 == 0 || idx == queries.size())) {
                progressJob.setProcessedItems(idx);
                evalJobRepository.save(progressJob);
            }
        }
        Map<String, Double> recallAtK = new LinkedHashMap<>();
        for (int k : topKs) {
            recallAtK.put(String.valueOf(k), round(average(recalls.get(k))));
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run_at", RUN_AT.format(now));
        report.put("run_tag", RUN_TAG.format(now));
        report.put("date", now.toLocalDate().toString());
        report.put("schema_version", golden.version());
        report.put("suite_key", suiteKey);
        report.put("strategy", strategyOverride != null && !strategyOverride.isBlank() ? strategyOverride : config.strategy());
        report.put("query_count", queries.size());
        report.put("min_score", eval.minScore());
        report.put("recall_at_k", recallAtK);
        report.put("mrr", round(average(mrrs)));
        report.put("empty_rate_positive", posTotal == 0 ? 0.0 : round(posEmpty * 1.0 / posTotal));
        report.put("empty_rate_negative", negTotal == 0 ? 0.0 : round(negEmpty * 1.0 / negTotal));
        Map<String, Double> latency = Map.of(
                "p50", round(EvalMetrics.percentile(latencies, 50)),
                "p95", round(EvalMetrics.percentile(latencies, 95)));
        report.put("latency_ms", latency);
        Map<String, Double> byCategoryRecall = new LinkedHashMap<>();
        byCategory.forEach((cat, values) -> byCategoryRecall.put(cat, round(average(values))));
        report.put("by_category_recall_at_3", byCategoryRecall);
        report.put("gates", eval.gates());
        report.put("badcases", Map.of(
                "positive_miss", positiveMiss,
                "negative_false_positive", negativeFp));
        List<String> gateFailures = checkProductionGates(report, eval.gates());
        report.put("gate_check", Map.of(
                "passed", gateFailures.isEmpty(),
                "failures", gateFailures));
        return report;
    }

    private void persistFullReport(
            EvalJobEntity job, Map<String, Object> report, EvalReportWriter.WrittenReport written) {
        @SuppressWarnings("unchecked")
        Map<String, Double> recallAtK = (Map<String, Double>) report.get("recall_at_k");
        double recall5 = recallAtK.getOrDefault("5", 0.0);
        double baseline = resolveBaselineRecallAt5();
        @SuppressWarnings("unchecked")
        Map<String, Object> gates = report.get("gates") instanceof Map<?, ?> pg
                ? (Map<String, Object>) pg
                : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> gateCheck = report.get("gate_check") instanceof Map<?, ?> gc
                ? (Map<String, Object>) gc
                : Map.of();
        boolean passedGate = !gates.isEmpty()
                ? Boolean.TRUE.equals(gateCheck.get("passed"))
                : recall5 >= baseline;
        EvalReportEntity entity = new EvalReportEntity();
        entity.setJobId(job.getId());
        entity.setRecallAt5(recall5);
        entity.setMrr((Double) report.get("mrr"));
        entity.setBaselineRecallAt5(baseline);
        entity.setPassedGate(passedGate);
        entity.setReportMdPath(written.mdPath() != null ? written.mdPath().toString() : written.mdObjectKey());
        entity.setReportObjectKey(written.jsonObjectKey());
        Map<String, Object> summary = buildSummary(report);
        Map<String, Object> failedSamples = extractFailedSamples(report);
        try {
            entity.setSummaryJson(objectMapper.writeValueAsString(summary));
            entity.setFailedSamplesJson(objectMapper.writeValueAsString(failedSamples));
        } catch (Exception e) {
            entity.setSummaryJson("{}");
            entity.setFailedSamplesJson("[]");
        }
        Map<String, Object> delta = new LinkedHashMap<>(report);
        delta.put("report_json_path", written.jsonObjectKey());
        delta.put("report_md_path", written.mdObjectKey());
        try {
            entity.setDeltaJson(objectMapper.writeValueAsString(delta));
        } catch (Exception e) {
            entity.setDeltaJson("{}");
        }
        evalReportRepository.save(entity);
        job.setReportId(entity.getId());
    }

    private List<RetrievalService.DocFragment> searchHits(
            String query,
            int topK,
            String tenantId,
            String kbId,
            String strategy,
            boolean rewrite,
            EffectiveRagConfig config) {
        PipelineSearchRequest request = PipelineSearchRequest.of(
                query, topK, tenantId, kbId, strategy, rewrite, false);
        PipelineSearchResult result = pipeline.searchWithConfig(request, config).block();
        return result != null ? result.results() : List.of();
    }

    private static Set<String> resolveRelevantNames(
            GoldenSetLoader.GoldenQuery query, Map<String, String> id2name) {
        Set<String> relevant = new LinkedHashSet<>();
        for (String docId : query.relevantDocIds()) {
            String name = id2name.get(docId);
            if (name != null) {
                relevant.add(name);
            }
        }
        return relevant;
    }

    private static Map<String, Object> buildPositiveMiss(
            GoldenSetLoader.GoldenQuery query,
            Set<String> relevant,
            List<RetrievalService.DocFragment> filtered,
            float minScore) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", query.id());
        row.put("query", query.query());
        row.put("category", query.category());
        row.put("expected", relevant.stream().sorted().toList());
        row.put("top3", filtered.stream().limit(3).map(EvaluateService::toTopHit).toList());
        row.put("first_rank", EvalMetrics.firstRelevantRank(filtered, relevant, minScore));
        return row;
    }

    private static Map<String, Object> buildNegativeFp(
            GoldenSetLoader.GoldenQuery query, List<RetrievalService.DocFragment> filtered) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", query.id());
        row.put("query", query.query());
        row.put("top3", filtered.stream().limit(3).map(EvaluateService::toTopHit).toList());
        return row;
    }

    private static Map<String, Object> toTopHit(RetrievalService.DocFragment hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("docName", hit.docName());
        row.put("score", round(hit.score()));
        return row;
    }

    @SuppressWarnings("unchecked")
    static List<String> checkProductionGates(Map<String, Object> report, Map<String, Object> gates) {
        List<String> failures = new ArrayList<>();
        Map<String, Double> recallAtK = (Map<String, Double>) report.get("recall_at_k");
        if (gates.get("recallAt3Min") instanceof Number g3
                && recallAtK.getOrDefault("3", 0.0) < g3.doubleValue()) {
            failures.add("Recall@3 " + recallAtK.get("3") + " 低于阈值 " + g3);
        }
        if (gates.get("recallAt5Min") instanceof Number g5
                && recallAtK.getOrDefault("5", 0.0) < g5.doubleValue()) {
            failures.add("Recall@5 " + recallAtK.get("5") + " 低于阈值 " + g5);
        }
        if (gates.get("mrrMin") instanceof Number mrrMin
                && ((Number) report.get("mrr")).doubleValue() < mrrMin.doubleValue()) {
            failures.add("MRR " + report.get("mrr") + " 低于阈值 " + mrrMin);
        }
        if (gates.get("emptyRatePositiveMax") instanceof Number emptyPosMax
                && ((Number) report.get("empty_rate_positive")).doubleValue() > emptyPosMax.doubleValue()) {
            failures.add("正例 EmptyRate " + report.get("empty_rate_positive") + " 高于阈值 " + emptyPosMax);
        }
        if (gates.get("emptyRateNegativeMin") instanceof Number emptyNegMin
                && ((Number) report.get("empty_rate_negative")).doubleValue() < emptyNegMin.doubleValue()) {
            failures.add("负例 EmptyRate " + report.get("empty_rate_negative") + " 低于阈值 " + emptyNegMin);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> latency = report.get("latency_ms") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        if (gates.get("latencyP95MsMax") instanceof Number p95Max
                && latency.get("p95") instanceof Number p95
                && p95.doubleValue() > p95Max.doubleValue()) {
            failures.add("P95 延迟 " + p95 + "ms 高于阈值 " + p95Max + "ms");
        }
        return failures;
    }

    private double resolveBaselineRecallAt5() {
        return evalReportRepository.findFirstByPassedGateTrueOrderByCreatedAtDesc()
                .map(report -> report.getRecallAt5() != null ? report.getRecallAt5() : evalProperties.getDefaultBaselineRecallAt5())
                .orElse(evalProperties.getDefaultBaselineRecallAt5());
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

    private static Map<String, Object> buildSummary(Map<String, Object> report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("recall_at_k", report.get("recall_at_k"));
        summary.put("mrr", report.get("mrr"));
        summary.put("query_count", report.get("query_count"));
        summary.put("gates", report.get("gates"));
        summary.put("gate_check", report.get("gate_check"));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractFailedSamples(Map<String, Object> report) {
        Map<String, Object> samples = new LinkedHashMap<>();
        if (report.get("badcases") instanceof Map<?, ?> badcases) {
            Map<String, Object> bc = (Map<String, Object>) badcases;
            Object positiveMiss = bc.get("positive_miss");
            if (positiveMiss instanceof List<?> list && !list.isEmpty()) {
                samples.put("positive_miss", list);
            }
            Object negativeFp = bc.get("negative_false_positive");
            if (negativeFp instanceof List<?> list && !list.isEmpty()) {
                samples.put("negative_false_positive", list);
            }
        }
        if (!samples.isEmpty()) {
            return samples;
        }
        if (report.get("failedSamples") instanceof List<?> list) {
            return Map.of("failedSamples", list);
        }
        return Map.of();
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

    private Map<String, Object> parseJsonMap(String json) {
        try {
            return objectMapper.readValue(json != null ? json : "{}", MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static double average(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static double round(float value) {
        return round((double) value);
    }

    private static String stringVal(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
