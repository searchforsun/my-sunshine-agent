package com.sunshine.rag.admin.eval;

import com.sunshine.rag.admin.config.ConfigResolveMode;
import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.entity.EvalJobEntity;
import com.sunshine.rag.repository.EvalJobRepository;
import com.sunshine.rag.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Golden set 全量评测编排 */
@Component
@RequiredArgsConstructor
public class EvalFullRunOrchestrator {

    private static final DateTimeFormatter RUN_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter RUN_TAG = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EffectiveConfigResolver effectiveConfigResolver;
    private final GoldenSetLoader goldenSetLoader;
    private final EvalRetrievalProbe retrievalProbe;
    private final EvalJobRepository evalJobRepository;

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
            List<RetrievalService.DocFragment> hits = retrievalProbe.searchHits(
                    query.query(), maxK, tenantId, kbId, strategyOverride, true, config);
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            latencies.add(latencyMs);
            List<RetrievalService.DocFragment> filtered = EvalMetrics.filterByMinScore(hits, eval.minScore());
            if (query.positive()) {
                Set<String> relevant = EvalRetrievalProbe.resolveRelevantNames(query, id2name);
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
        row.put("top3", filtered.stream().limit(3).map(EvalFullRunOrchestrator::toTopHit).toList());
        row.put("first_rank", EvalMetrics.firstRelevantRank(filtered, relevant, minScore));
        return row;
    }

    private static Map<String, Object> buildNegativeFp(
            GoldenSetLoader.GoldenQuery query, List<RetrievalService.DocFragment> filtered) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", query.id());
        row.put("query", query.query());
        row.put("top3", filtered.stream().limit(3).map(EvalFullRunOrchestrator::toTopHit).toList());
        return row;
    }

    private static Map<String, Object> toTopHit(RetrievalService.DocFragment hit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("docName", hit.docName());
        row.put("score", round(hit.score()));
        return row;
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
}
