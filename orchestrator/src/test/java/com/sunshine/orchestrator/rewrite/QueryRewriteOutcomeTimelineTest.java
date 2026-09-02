package com.sunshine.orchestrator.rewrite;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteOutcomeTimelineTest {

    @Test
    void timelineDetailUsesTraceScenarioLabelForRag() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(
                "rag", "我今天打车了", "因公打车报销流程 交通费制度", 2568L, "优化检索词");
        assertThat(outcome.timelineDetail())
                .startsWith("优化检索词")
                .contains("原问题：我今天打车了")
                .contains("优化后：因公打车报销流程 交通费制度")
                .contains("2.6s");
    }

    @Test
    void hydeTimelineDetailUsesTraceScenarioLabel() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(
                "hyde", "我今天打车了", "员工因公外出产生的交通费用", 3021L, "生成参考文档");
        assertThat(outcome.timelineDetail())
                .startsWith("生成参考文档")
                .contains("参考文档：");
    }

    @Test
    void emptyRecallSkippedUsesTraceScenarioLabel() {
        QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                "empty-recall", "我明天要打车", 800L, "换种方式再查");
        assertThat(skipped.timelineDetail())
                .contains("换种方式再查")
                .contains("未能生成新的检索词");
    }

    @Test
    void intentOutcomeWithoutLabel_noPrefixInTimelineDetail() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("intent", "待审批", "查询待审批报销", 15L);
        assertThat(outcome.timelineDetail())
                .startsWith("原问题：待审批")
                .doesNotStartWith("补全问句");
    }
}
