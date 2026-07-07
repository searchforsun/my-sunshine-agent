package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.eval.dto.EvalSuggestResult;
import com.sunshine.rag.config.RagEvalProperties;
import com.sunshine.rag.entity.EvalJobEntity;
import com.sunshine.rag.entity.EvalReportEntity;
import com.sunshine.rag.repository.EvalReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 评测报告落库与读视图解析 */
@Component
@RequiredArgsConstructor
public class EvalReportPersister {

    private final EvalReportRepository evalReportRepository;
    private final RagEvalProperties evalProperties;
    private final ObjectMapper objectMapper;

    void persistFullReport(
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

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parseFailedSamples(EvalReportEntity report) {
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
                Map<String, Object> parsed = objectMapper.readValue(
                        report.getFailedSamplesJson(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
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

    EvalSuggestResult parseSuggestions(EvalReportEntity report) {
        if (report.getSuggestionsJson() == null || report.getSuggestionsJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(report.getSuggestionsJson(), EvalSuggestResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    Map<String, Object> parseJsonMap(String json) {
        try {
            return objectMapper.readValue(json != null ? json : "{}", new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    double resolveBaselineRecallAt5() {
        return evalReportRepository.findFirstByPassedGateTrueOrderByCreatedAtDesc()
                .map(report -> report.getRecallAt5() != null ? report.getRecallAt5() : evalProperties.getDefaultBaselineRecallAt5())
                .orElse(evalProperties.getDefaultBaselineRecallAt5());
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
}
