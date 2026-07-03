package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.eval.dto.ConfigSuggestionItem;
import com.sunshine.rag.admin.eval.dto.EvalSuggestResult;
import com.sunshine.rag.entity.EvalReportEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalSuggestContextBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildIncludesBothBadcaseTypesAndFailureModes() throws Exception {
        EvalReportEntity report = new EvalReportEntity();
        report.setPassedGate(false);
        report.setRecallAt5(0.9821);
        report.setMrr(0.9102);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("empty_rate_positive", 0.0);
        delta.put("empty_rate_negative", 0.0);
        delta.put("gate_check", Map.of(
                "passed", false,
                "failures", List.of("负例 EmptyRate 0.0 低于阈值 0.95")));
        delta.put("badcases", Map.of(
                "positive_miss", List.of(Map.of(
                        "query", "直属主管审批职责是什么",
                        "expected", List.of("公司请假流程规范"),
                        "top3", List.of(Map.of("docName", "财务审批权限矩阵", "score", 0.82)))),
                "negative_false_positive", List.of(Map.of(
                        "query", "帮我写一首唐诗",
                        "top3", List.of(Map.of("docName", "员工场景速查与多制度交叉指引", "score", 0.51))))));
        report.setDeltaJson(objectMapper.writeValueAsString(delta));
        Map<String, Object> ctx = EvalSuggestContextBuilder.build(report, objectMapper);
        assertThat(ctx.get("gateFailures")).asList().hasSize(1);
        assertThat(ctx.get("failureModes")).asList()
                .contains("NEGATIVE_EMPTY_RATE_LOW", "POSITIVE_RECALL_MISS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> positiveMiss = (List<Map<String, Object>>) ctx.get("positiveMiss");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> negativeFp = (List<Map<String, Object>>) ctx.get("negativeFalsePositive");
        assertThat(positiveMiss).hasSize(1);
        assertThat(negativeFp).hasSize(1);
        assertThat(ctx.get("tuningHints")).asList().isNotEmpty();
        assertThat(ctx.get("configRuntimeNotes")).asList().isNotEmpty();
    }

    @Test
    void classifyFailureModesFromGateFailures() {
        List<String> modes = EvalSuggestContextBuilder.classifyFailureModes(
                List.of("Recall@5 0.97 低于阈值 0.98", "正例 EmptyRate 0.05 高于阈值 0.0"),
                Map.of(),
                List.of(),
                List.of());
        assertThat(modes).contains("RECALL_AT_5_LOW", "POSITIVE_EMPTY_RATE_HIGH");
    }
}
