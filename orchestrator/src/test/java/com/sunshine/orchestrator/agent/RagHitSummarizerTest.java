package com.sunshine.orchestrator.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagHitSummarizerTest {

    @Test
    void summarize_rawWithHits_returnsOneLineDocNames() {
        String raw = """
                知识库检索结果（共 3 条）：
                来源文档：公司请假流程规范

                【公司请假流程规范 | 片段 1】
                关键岗位、项目节点、值班期间请假
                """;
        assertThat(RagHitSummarizer.summarize(raw))
                .isEqualTo("命中 3 条，来源：公司请假流程规范");
    }

    @Test
    void summarize_fragmentContainsWeiZhaoDao_doesNotTreatAsZeroHit() {
        String raw = """
                知识库检索结果（共 3 条）：
                来源文档：公司请假流程规范

                【公司请假流程规范 | 片段 1】
                若未找到直属主管，请联系 HR。
                """;
        assertThat(RagHitSummarizer.summarize(raw)).contains("命中 3 条");
    }

    @Test
    void summarize_emptyHeader_returnsZeroHit() {
        assertThat(RagHitSummarizer.summarize("未找到相关知识库内容。请如实告知用户，勿编造制度名称或条款。"))
                .isEqualTo("命中 0 条");
    }
}
