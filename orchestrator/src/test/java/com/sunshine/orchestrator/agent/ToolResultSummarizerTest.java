package com.sunshine.orchestrator.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultSummarizerTest {

    @Test
    void financeSummary_notHitZero() {
        String text = """
                财务消息汇总：
                - status=pending | count=3 | totalAmount=124140.50
                """;
        assertThat(ToolResultSummarizer.summarizeByKind("finance-summary", text))
                .isEqualTo("pending 3 条，合计 ¥124140.50");
    }

    @Test
    void financeDetail_extractsTitle() {
        String text = """
                财务消息详情：
                - id=1001
                - 标题=Q2 差旅报销审批
                - 类型=reimbursement
                """;
        assertThat(ToolResultSummarizer.summarizeByKind("finance-detail", text))
                .isEqualTo("Q2 差旅报销审批");
    }

    @Test
    void nonRagTool_doesNotUseHitZero() {
        assertThat(ToolResultSummarizer.summarizeByKind("oa-tasks", "暂无待办任务。"))
                .isEqualTo("0 条 OA 待办");
        assertThat(ToolResultSummarizer.summarizeByKind("finance-detail", ""))
                .isEqualTo("无结果");
    }

    @Test
    void ragTool_stillUsesHitSummarizer() {
        assertThat(ToolResultSummarizer.summarizeByKind("hit-count", "未找到相关知识库内容。"))
                .isEqualTo("命中 0 条");
    }

    @Test
    void summarizeByToolId_mapsCatalogKinds() {
        assertThat(ToolResultSummarizer.summarize("list_finance_messages", "共 2 条"))
                .isEqualTo("2 条财务消息");
    }
}
