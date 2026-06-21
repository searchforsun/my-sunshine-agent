package com.sunshine.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.metrics.RagSearchMetrics;
import com.sunshine.rag.model.RetrievalCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RerankServiceTest {

    private RerankService service;
    private RagRerankProperties props;

    @BeforeEach
    void setUp() {
        props = new RagRerankProperties();
        props.setEnabled(false);
        props.setMinRelevance(0.25f);
        service = new RerankService(props, new ObjectMapper(), new RagSearchMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void parseResultsMapsRelevanceScore() throws Exception {
        List<RetrievalCandidate> input = List.of(
                new RetrievalCandidate("a#0", "A", "弱相关", 0.5f, RetrievalCandidate.SOURCE_RRF),
                new RetrievalCandidate("b#0", "B", "强相关", 0.5f, RetrievalCandidate.SOURCE_RRF));
        String json = """
                {
                  "output": {
                    "results": [
                      {"index": 1, "relevance_score": 0.91},
                      {"index": 0, "relevance_score": 0.22}
                    ]
                  }
                }
                """;
        RerankService.RerankOutcome outcome = service.parseResults(json, input, 2);
        List<RetrievalCandidate> ranked = service.finalizeRerank(outcome, input, 2);
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).docName()).isEqualTo("B");
        assertThat(ranked.get(0).score()).isGreaterThanOrEqualTo(0.48f);
    }

    @Test
    void hybridFallbackWhenAllBelowMinRelevance() throws Exception {
        List<RetrievalCandidate> input = List.of(
                new RetrievalCandidate("a#0", "A", "无关", 1.0f, RetrievalCandidate.SOURCE_RRF));
        String json = """
                {
                  "output": {
                    "results": [
                      {"index": 0, "relevance_score": 0.18}
                    ]
                  }
                }
                """;
        RerankService.RerankOutcome outcome = service.parseResults(json, input, 5);
        List<RetrievalCandidate> ranked = service.finalizeRerank(outcome, input, 5);
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).docName()).isEqualTo("A");
    }
}
