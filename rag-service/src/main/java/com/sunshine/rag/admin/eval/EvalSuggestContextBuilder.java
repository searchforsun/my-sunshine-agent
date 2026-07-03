package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.entity.EvalReportEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为 Suggest LLM 组装评测上下文：门禁、指标、双类 badcase、失败模式与调参约束 */
public final class EvalSuggestContextBuilder {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int SAMPLE_CAP = 15;

    private EvalSuggestContextBuilder() {
    }

    public static Map<String, Object> build(EvalReportEntity report, ObjectMapper objectMapper) {
        Map<String, Object> delta = parseJson(report.getDeltaJson(), objectMapper);
        Map<String, Object> summary = parseJson(report.getSummaryJson(), objectMapper);
        List<String> gateFailures = extractGateFailures(summary, delta);
        Map<String, Object> metrics = buildMetrics(report, delta, summary);
        Map<String, List<Map<String, Object>>> badcases = loadBadcases(report, delta, objectMapper);
        List<Map<String, Object>> positiveMiss = badcases.getOrDefault("positive_miss", List.of());
        List<Map<String, Object>> negativeFp = badcases.getOrDefault("negative_false_positive", List.of());
        List<String> failureModes = classifyFailureModes(gateFailures, metrics, positiveMiss, negativeFp);
        List<String> tuningHints = buildTuningHints(failureModes, positiveMiss, negativeFp);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("gateFailures", gateFailures);
        ctx.put("failureModes", failureModes);
        ctx.put("metrics", metrics);
        ctx.put("tuningHints", tuningHints);
        ctx.put("configRuntimeNotes", configRuntimeNotes());
        ctx.put("positiveMiss", cap(positiveMiss));
        ctx.put("negativeFalsePositive", cap(negativeFp));
        return ctx;
    }

    private static List<String> configRuntimeNotes() {
        return List.of(
                "rerank.minRelevance 在 kb bundle 可编辑但尚未接入 per-kb 运行时；"
                        + "全局 relevance 门禁见 Nacos rag.rerank.min-relevance（RerankService 生效）",
                "负例误召回优先调 search.minScore 或 rerank.minScore；评测过滤使用 eval minScore（通常与 search.minScore 一致）");
    }

    private static List<String> buildTuningHints(
            List<String> failureModes,
            List<Map<String, Object>> positiveMiss,
            List<Map<String, Object>> negativeFp) {
        List<String> hints = new ArrayList<>();
        if (failureModes.contains(FailureMode.NEGATIVE_EMPTY_RATE_LOW.name())) {
            hints.add("负例 EmptyRate 未达标：离题 query 被误召回。"
                    + "应提高 search.minScore 或 rerank.minScore，禁止降低阈值或 minRelevance");
        }
        if (failureModes.contains(FailureMode.POSITIVE_EMPTY_RATE_HIGH.name())) {
            hints.add("正例 EmptyRate 过高：应查 query 是否过短、rewrite 是否改偏，或适度降低 search.minScore");
        }
        if (failureModes.contains(FailureMode.RECALL_AT_3_LOW.name())
                || failureModes.contains(FailureMode.RECALL_AT_5_LOW.name())
                || failureModes.contains(FailureMode.POSITIVE_RECALL_MISS.name())) {
            hints.add("正例召回未命中：优先检查 rewrite.rag 是否将 query 绑定到目标制度名，"
                    + "避免命中交叉索引文档（如财务审批权限矩阵、员工场景速查）");
            hints.add("rewrite.hyde 仅在首检零命中时触发，对「有高分的错误文档」场景作用有限");
        }
        if (!negativeFp.isEmpty() && !failureModes.contains(FailureMode.NEGATIVE_EMPTY_RATE_LOW.name())) {
            hints.add("存在负例误召回样本但未触发负例 EmptyRate 门禁，仍建议关注阈值是否偏松");
        }
        if (!positiveMiss.isEmpty() && hints.isEmpty()) {
            hints.add("门禁已通过但存在正例 miss，可微调 rewrite Prompt，慎动检索阈值");
        }
        return hints;
    }

    static List<String> classifyFailureModes(
            List<String> gateFailures,
            Map<String, Object> metrics,
            List<Map<String, Object>> positiveMiss,
            List<Map<String, Object>> negativeFp) {
        List<String> modes = new ArrayList<>();
        for (String failure : gateFailures) {
            if (failure.contains("正例 EmptyRate")) {
                modes.add(FailureMode.POSITIVE_EMPTY_RATE_HIGH.name());
            } else if (failure.contains("负例 EmptyRate")) {
                modes.add(FailureMode.NEGATIVE_EMPTY_RATE_LOW.name());
            } else if (failure.contains("Recall@3")) {
                modes.add(FailureMode.RECALL_AT_3_LOW.name());
            } else if (failure.contains("Recall@5")) {
                modes.add(FailureMode.RECALL_AT_5_LOW.name());
            } else if (failure.contains("MRR")) {
                modes.add(FailureMode.MRR_LOW.name());
            } else if (failure.contains("P95 延迟")) {
                modes.add(FailureMode.LATENCY_HIGH.name());
            }
        }
        if (!positiveMiss.isEmpty() && !modes.contains(FailureMode.RECALL_AT_3_LOW.name())
                && !modes.contains(FailureMode.RECALL_AT_5_LOW.name())) {
            modes.add(FailureMode.POSITIVE_RECALL_MISS.name());
        }
        if (!negativeFp.isEmpty() && !modes.contains(FailureMode.NEGATIVE_EMPTY_RATE_LOW.name())) {
            modes.add(FailureMode.NEGATIVE_FALSE_POSITIVE.name());
        }
        return modes;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractGateFailures(Map<String, Object> summary, Map<String, Object> delta) {
        Object gateCheck = summary.get("gate_check");
        if (!(gateCheck instanceof Map<?, ?> map)) {
            gateCheck = delta.get("gate_check");
        }
        if (gateCheck instanceof Map<?, ?> check) {
            Object failures = ((Map<String, Object>) check).get("failures");
            if (failures instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildMetrics(
            EvalReportEntity report, Map<String, Object> delta, Map<String, Object> summary) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("recallAt5", firstNonNull(report.getRecallAt5(), delta.get("recall_at_k"), summary.get("recall_at_k")));
        metrics.put("mrr", firstNonNull(report.getMrr(), delta.get("mrr"), summary.get("mrr")));
        metrics.put("emptyRatePositive", firstNonNull(delta.get("empty_rate_positive"), summary.get("empty_rate_positive")));
        metrics.put("emptyRateNegative", firstNonNull(delta.get("empty_rate_negative"), summary.get("empty_rate_negative")));
        metrics.put("recallAtK", firstNonNull(delta.get("recall_at_k"), summary.get("recall_at_k")));
        metrics.put("latencyMs", firstNonNull(delta.get("latency_ms"), summary.get("latency_ms")));
        metrics.put("minScore", delta.get("min_score"));
        metrics.put("gates", firstNonNull(delta.get("gates"), summary.get("gates")));
        metrics.put("passedGate", report.getPassedGate());
        return metrics;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, List<Map<String, Object>>> loadBadcases(
            EvalReportEntity report, Map<String, Object> delta, ObjectMapper objectMapper) {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("positive_miss", List.of());
        result.put("negative_false_positive", List.of());
        if (report.getFailedSamplesJson() != null && !report.getFailedSamplesJson().isBlank()) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(report.getFailedSamplesJson(), MAP_TYPE);
                mergeBadcaseList(result, parsed, "positive_miss");
                mergeBadcaseList(result, parsed, "negative_false_positive");
                if (!result.get("positive_miss").isEmpty() || !result.get("negative_false_positive").isEmpty()) {
                    return result;
                }
            } catch (Exception ignored) {
                // 回退 delta_json
            }
        }
        Object badcases = delta.get("badcases");
        if (badcases instanceof Map<?, ?> map) {
            mergeBadcaseList(result, (Map<String, Object>) map, "positive_miss");
            mergeBadcaseList(result, (Map<String, Object>) map, "negative_false_positive");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void mergeBadcaseList(
            Map<String, List<Map<String, Object>>> target,
            Map<String, Object> source,
            String key) {
        Object value = source.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            target.put(key, (List<Map<String, Object>>) list);
        }
    }

    private static List<Map<String, Object>> cap(List<Map<String, Object>> samples) {
        return samples.size() > SAMPLE_CAP ? samples.subList(0, SAMPLE_CAP) : samples;
    }

    private static Map<String, Object> parseJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    enum FailureMode {
        POSITIVE_RECALL_MISS,
        POSITIVE_EMPTY_RATE_HIGH,
        NEGATIVE_EMPTY_RATE_LOW,
        NEGATIVE_FALSE_POSITIVE,
        RECALL_AT_3_LOW,
        RECALL_AT_5_LOW,
        MRR_LOW,
        LATENCY_HIGH
    }
}
