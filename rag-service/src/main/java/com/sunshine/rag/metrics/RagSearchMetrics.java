package com.sunshine.rag.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * RAG 检索 Micrometer 指标（Task 3.4.6）。
 * <p>
 * 命名约定：rag_search_*、rag_empty_total、rag_rerank_duration_seconds
 */
@Component
public class RagSearchMetrics {

    private final MeterRegistry registry;

    public RagSearchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(String strategy, long startNanos, int hitCount) {
        recordDuration(strategy, startNanos, "success");
        if (hitCount == 0) {
            emptyCounter(strategy).increment();
        }
        hitSummary(strategy).record(hitCount);
    }

    public void recordError(String strategy, long startNanos) {
        recordDuration(strategy, startNanos, "error");
        errorCounter(strategy).increment();
    }

    public void recordVectorAnchorEmpty() {
        Counter.builder("rag_vector_anchor_empty_total")
                .description("hybrid+rerank 向量锚点未达阈导致的空召回")
                .register(registry)
                .increment();
    }

    public void recordRerank(long startNanos, String status) {
        Timer.builder("rag_rerank_duration_seconds")
                .description("DashScope Rerank API 耗时")
                .tag("status", status)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        if ("error".equals(status)) {
            Counter.builder("rag_rerank_errors_total")
                    .description("Rerank API 或解析失败次数")
                    .register(registry)
                    .increment();
        }
    }

    private void recordDuration(String strategy, long startNanos, String status) {
        Timer.builder("rag_search_duration_seconds")
                .description("RAG 检索端到端耗时")
                .tag("strategy", strategy)
                .tag("status", status)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        Counter.builder("rag_search_requests_total")
                .description("RAG 检索请求数")
                .tag("strategy", strategy)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    private Counter emptyCounter(String strategy) {
        return Counter.builder("rag_empty_total")
                .description("检索结果为空（过滤后 0 命中）")
                .tag("strategy", strategy)
                .register(registry);
    }

    private Counter errorCounter(String strategy) {
        return Counter.builder("rag_search_errors_total")
                .description("检索链路异常次数")
                .tag("strategy", strategy)
                .register(registry);
    }

    private DistributionSummary hitSummary(String strategy) {
        return DistributionSummary.builder("rag_search_hits")
                .description("单次检索有效命中条数")
                .tag("strategy", strategy)
                .register(registry);
    }
}
