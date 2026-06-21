package com.sunshine.orchestrator.rewrite;

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
        QueryRewriteTrace.bind("m1");
        QueryRewriteTrace.record("m1", QueryRewriteOutcome.of("intent", "待审批", "查询待审批报销", 12L));
        QueryRewriteTrace.record("m1", QueryRewriteOutcome.of("rag", "报差旅", "公司差旅费报销管理办法", 8L));

        assertThat(QueryRewriteTrace.intentOutcome("m1")).isPresent();
        assertThat(QueryRewriteTrace.combinedTimelineDetail("m1"))
                .contains("改写前：待审批")
                .contains("改写后：查询待审批报销");

        QueryRewriteTrace.AuditRewriteSummary summary = QueryRewriteTrace.auditSummary("m1");
        assertThat(summary.rewriteApplied()).isTrue();
        assertThat(summary.rewriteLatencyMs()).isEqualTo(20L);
    }
}
