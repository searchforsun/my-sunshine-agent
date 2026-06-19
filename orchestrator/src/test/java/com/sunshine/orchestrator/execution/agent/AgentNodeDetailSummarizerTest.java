package com.sunshine.orchestrator.execution.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentNodeDetailSummarizerTest {

    @Test
    void summarize_withAnswer_usesFirstMeaningfulLine() {
        String answer = """
                # 分析报告

                待审批 5 笔单据中，3 笔存在合规风险，建议优先处理编号 1004。
                """;
        assertThat(AgentNodeDetailSummarizer.summarize(answer, 0))
                .isEqualTo("待审批 5 笔单据中，3 笔存在合规风险，建议优先处理编号 1004。");
    }

    @Test
    void summarize_withoutAnswer_usesToolCallCount() {
        assertThat(AgentNodeDetailSummarizer.summarize("", 2))
                .isEqualTo("已完成 2 次工具调用的综合分析");
    }

    @Test
    void summarize_withoutAnswer_usesReasoningTail() {
        String reasoning = """
                规划工具调用…
                待审批 5 笔单据中，3 笔存在合规风险，建议优先处理编号 1004。
                """;
        assertThat(AgentNodeDetailSummarizer.summarize("", reasoning, 0))
                .contains("合规风险");
    }

    @Test
    void summarize_empty_fallsBackToDone() {
        assertThat(AgentNodeDetailSummarizer.summarize(null, 0))
                .isEqualTo("智能体分析完成");
    }

    @Test
    void summarize_longText_notTruncated() {
        String line = "要完成本次合规审查，需要补充该报销的费用明细".repeat(5);
        String preview = AgentNodeDetailSummarizer.summarize(line, 0);
        assertThat(preview).isEqualTo(line);
        assertThat(preview).doesNotContain("\n").doesNotEndWith("…");
    }

    @Test
    void summarize_skipsMarkdownReport_picksConclusionLine() {
        String answer = """
                ---

                ## 综合判断：项目预算审批前景分析

                ### 四、结论

                > **基于现有数据，我无法给出"能过"或"不能过"的确定性结论。**

                | 维度 | 数据 |
                |---|---|
                | pending | 3 笔 |
                """;
        assertThat(AgentNodeDetailSummarizer.summarize(answer, 0))
                .contains("无法给出")
                .doesNotContain("---")
                .doesNotContain("|");
    }
}
