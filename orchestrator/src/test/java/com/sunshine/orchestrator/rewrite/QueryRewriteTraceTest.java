package com.sunshine.orchestrator.rewrite;

import com.sunshine.orchestrator.config.AgentRewriteProperties;
import com.sunshine.orchestrator.processing.RewriteTimelineLabels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteTraceTest {

    @AfterEach
    void tearDown() {
        QueryRewriteTrace.clear("m1");
        QueryRewriteTrace.clear("m-par");
    }

    @Test
    void recordsAndSummarizesRewriteEvents() {
        AgentRewriteProperties props = new AgentRewriteProperties();
        AgentRewriteProperties.Timeline timeline = new AgentRewriteProperties.Timeline();
        timeline.setIntent("补全问句");
        props.setTimeline(timeline);
        RewriteTimelineLabels.bind(props);
        QueryRewriteTrace.bind("m1");
        QueryRewriteTrace.record("m1", QueryRewriteOutcome.of("intent", "待审批", "查询待审批报销", 12L));
        QueryRewriteTrace.record("m1",
                QueryRewriteOutcome.of("rag", "报差旅", "公司差旅费报销管理办法", 8L, "优化检索词"));

        assertThat(QueryRewriteTrace.intentOutcome("m1")).isPresent();
        assertThat(QueryRewriteTrace.combinedTimelineDetail("m1"))
                .contains("补全问句")
                .contains("原问题：待审批")
                .contains("优化检索词")
                .contains("优化后：查询待审批报销");

        assertThat(QueryRewriteTrace.combinedRagTimelineDetail("m1"))
                .contains("优化检索词")
                .doesNotContain("补全问句");

        assertThat(QueryRewriteTrace.combinedRagTimelineDetailSince("m1", 1))
                .contains("优化检索词")
                .doesNotContain("补全问句");
        assertThat(QueryRewriteTrace.combinedRagTimelineDetailSince("m1", 2)).isNull();

        QueryRewriteTrace.AuditRewriteSummary summary = QueryRewriteTrace.auditSummary("m1");
        assertThat(summary.rewriteApplied()).isTrue();
        assertThat(summary.rewriteLatencyMs()).isEqualTo(20L);
        RewriteTimelineLabels.bind(null);
    }

    @Test
    void parallelRagStep_outcomesIsolatedByStepId() {
        QueryRewriteTrace.bind("m-par");
        String stepA = "node-rag-a";
        String stepB = "node-rag-b";
        QueryRewriteOutcome rewriteA = QueryRewriteOutcome.of(
                "rag", "q", "rewrite-a", 10L, "优化检索词");
        QueryRewriteOutcome rewriteB = QueryRewriteOutcome.of(
                "rag", "q", "rewrite-b", 11L, "优化检索词");
        QueryRewriteTrace.recordForRagStep("m-par", stepA, rewriteA);
        QueryRewriteTrace.recordForRagStep("m-par", stepB, rewriteB);

        assertThat(QueryRewriteTrace.combinedRagTimelineDetailForStep("m-par", stepA))
                .contains("rewrite-a")
                .doesNotContain("rewrite-b");
        assertThat(QueryRewriteTrace.combinedRagTimelineDetailForStep("m-par", stepB))
                .contains("rewrite-b")
                .doesNotContain("rewrite-a");

        QueryRewriteTrace.clear("m-par");
    }

    @Test
    void beginRagSpan_clearsPriorOutcomesForSameStep_loopRoundsIsolated() {
        QueryRewriteTrace.bind("m-loop");
        String step = "node-rag-x";
        QueryRewriteTrace.beginRagSpan(step, "m-loop");
        QueryRewriteTrace.record("m-loop", QueryRewriteOutcome.of(
                "rag", "q1", "rewrite-r1", 5L, "优化检索词"));
        QueryRewriteTrace.recordForRagStep("m-loop", step, QueryRewriteOutcome.of(
                "rag", "q1", "rewrite-r1", 5L, "优化检索词"));
        QueryRewriteTrace.endRagSpan(step, "m-loop");
        assertThat(QueryRewriteTrace.combinedRagTimelineDetailForStep("m-loop", step))
                .contains("rewrite-r1");

        QueryRewriteTrace.beginRagSpan(step, "m-loop");
        QueryRewriteTrace.record("m-loop", QueryRewriteOutcome.of(
                "rag", "q2", "rewrite-r2", 6L, "优化检索词"));
        QueryRewriteTrace.recordForRagStep("m-loop", step, QueryRewriteOutcome.of(
                "rag", "q2", "rewrite-r2", 6L, "优化检索词"));
        QueryRewriteTrace.endRagSpan(step, "m-loop");

        assertThat(QueryRewriteTrace.combinedRagTimelineDetailForStep("m-loop", step))
                .contains("rewrite-r2")
                .doesNotContain("rewrite-r1");

        QueryRewriteTrace.clear("m-loop");
    }
}
