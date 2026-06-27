package com.sunshine.orchestrator.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagClientResponseTest {

    @Test
    void parseSearchResults_unwrapsREnvelope() {
        Map<String, Object> body = Map.of(
                "code", 200,
                "data", Map.of(
                        "query", "差旅报销管理办法",
                        "results", List.of(
                                Map.of("docName", "差旅费管理办法", "content", "正文", "score", 0.82)
                        )));
        List<RagClient.RagHit> hits = RagClient.parseSearchResults(body, "差旅报销管理办法");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).docName()).isEqualTo("差旅费管理办法");
    }

    @Test
    void parseSearchResults_acceptsLegacyFlatBody() {
        Map<String, Object> body = Map.of(
                "results", List.of(
                        Map.of("docName", "报销制度", "content", "c", "score", 0.5)
                ));
        assertThat(RagClient.parseSearchResults(body, "q")).hasSize(1);
    }

    @Test
    void parseSearchResults_emptyWhenNoResults() {
        assertThat(RagClient.parseSearchResults(Map.of("code", 200, "data", Map.of()), "q")).isEmpty();
    }
}
