package com.sunshine.tool.summary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolOutputSummarizerTest {

    private final ToolResultLabelService labels = new ToolResultLabelService(new com.sunshine.tool.config.ToolTimelineProperties());
    private final ToolOutputSummarizer summarizer = new ToolOutputSummarizer(
            labels, new RagHitSummarizer(labels));

    @Test
    void financeSummary_notHitZero() {
        String text = """
                财务消息汇总：
                - status=pending | count=3 | totalAmount=124140.50
                """;
        assertThat(summarizer.summarizeByKind("finance-summary", text))
                .isEqualTo("pending 3 条，合计 ¥124140.50");
    }

    @Test
    void ragHit_zeroHit() {
        assertThat(summarizer.summarizeByKind("hit-count", "未找到相关知识库内容。"))
                .isEqualTo("命中 0 条");
    }

    @Test
    void financeList_withCount() {
        assertThat(summarizer.summarizeByKind("finance-list", "共 2 条"))
                .isEqualTo("2 条财务消息");
    }
}
