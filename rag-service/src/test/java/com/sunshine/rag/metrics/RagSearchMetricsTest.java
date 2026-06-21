package com.sunshine.rag.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagSearchMetricsTest {

    private SimpleMeterRegistry registry;
    private RagSearchMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RagSearchMetrics(registry);
    }

    @Test
    void recordSuccessIncrementsRequestsAndHits() {
        metrics.recordSuccess("vector", System.nanoTime() - 50_000_000L, 3);
        assertThat(registry.get("rag_search_requests_total")
                .tag("strategy", "vector")
                .tag("status", "success")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("rag_search_hits")
                .tag("strategy", "vector")
                .summary()
                .totalAmount()).isEqualTo(3.0);
    }

    @Test
    void recordSuccessWithZeroHitsIncrementsEmpty() {
        metrics.recordSuccess("hybrid_rerank", System.nanoTime() - 10_000_000L, 0);
        Counter empty = registry.get("rag_empty_total").tag("strategy", "hybrid_rerank").counter();
        assertThat(empty.count()).isEqualTo(1.0);
    }

    @Test
    void recordErrorIncrementsErrorCounters() {
        metrics.recordError("hybrid", System.nanoTime() - 5_000_000L);
        assertThat(registry.get("rag_search_requests_total")
                .tag("strategy", "hybrid")
                .tag("status", "error")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("rag_search_errors_total")
                .tag("strategy", "hybrid")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void recordRerankTracksDurationAndErrors() {
        metrics.recordRerank(System.nanoTime() - 100_000_000L, "success");
        metrics.recordRerank(System.nanoTime() - 20_000_000L, "error");
        assertThat(registry.get("rag_rerank_duration_seconds").tag("status", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("rag_rerank_duration_seconds").tag("status", "error").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("rag_rerank_errors_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordVectorAnchorEmpty() {
        metrics.recordVectorAnchorEmpty();
        assertThat(registry.get("rag_vector_anchor_empty_total").counter().count()).isEqualTo(1.0);
    }
}
