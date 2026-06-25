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
    }

    @Test
    void recordsAndSummarizesRewriteEvents() {
        AgentRewriteProperties props = new AgentRewriteProperties();
        AgentRewriteProperties.Timeline timeline = new AgentRewriteProperties.Timeline();
        timeline.setIntent("补全问句");
        timeline.setRag("优化检索词");
        props.setTimeline(timeline);
        RewriteTimelineLabels.bind(props);
        QueryRewriteTrace.bind("m1");
        QueryRewriteTrace.record("m1", QueryRewriteOutcome.of("intent", "待审批", "查询待审批报销", 12L));
        QueryRewriteTrace.record("m1", QueryRewriteOutcome.of("rag", "报差旅", "公司差旅费报销管理办法", 8L));

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
}
