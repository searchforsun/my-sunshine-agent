package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagClientTest {

    @Test
    void parseSearchResponseExtractsHitsAndEffectiveQuery() {
        Map<String, Object> response = Map.of(
                "data", Map.of(
                        "query", "年假",
                        "effectiveQuery", "年假 请假 制度",
                        "results", List.of(Map.of(
                                "docName", "考勤制度",
                                "content", "年假5天",
                                "score", 0.82)),
                        "trace", Map.of(
                                "searchCount", 1,
                                "stages", List.of(Map.of(
                                        "name", "rag",
                                        "applied", true,
                                        "from", "年假",
                                        "to", "年假 请假 制度",
                                        "latencyMs", 12,
                                        "scenarioLabel", "优化检索词")))));
        RagClient.RagSearchResult result = RagClient.parseSearchResponse(response, "年假");
        assertThat(result.hits()).hasSize(1);
        assertThat(result.effectiveQuery()).isEqualTo("年假 请假 制度");
        assertThat(result.traceOutcomes()).hasSize(1);
        assertThat(result.traceOutcomes().get(0).scenario()).isEqualTo("rag");
        assertThat(result.traceOutcomes().get(0).applied()).isTrue();
        assertThat(result.traceOutcomes().get(0).scenarioLabel()).isEqualTo("优化检索词");
    }

    @Test
    void parseTraceOutcomesHandlesEmptyRecall() {
        List<?> stages = List.of(Map.of(
                "name", "empty-recall",
                "applied", true,
                "from", "口语问",
                "to", "alt1；alt2",
                "latencyMs", 20L));
        var outcomes = RagClient.parseTraceOutcomes(Map.of("stages", stages));
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).scenario()).isEqualTo("empty-recall");
    }
}
