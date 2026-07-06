package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagClientResponseTest {

    @Test
    void parseSearchResponse_unwrapsREnvelope() {
        Map<String, Object> body = Map.of(
                "code", 200,
                "data", Map.of(
                        "query", "差旅报销管理办法",
                        "effectiveQuery", "差旅报销管理办法",
                        "results", List.of(
                                Map.of("docName", "差旅费管理办法", "content", "正文", "score", 0.82)
                        )));
        RagClient.RagSearchResult result = RagClient.parseSearchResponse(body, "差旅报销管理办法");
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).docName()).isEqualTo("差旅费管理办法");
    }

    @Test
    void parseSearchResponse_emptyWhenNoResults() {
        assertThat(RagClient.parseSearchResponse(Map.of("code", 200, "data", Map.of()), "q").hits()).isEmpty();
    }
}
