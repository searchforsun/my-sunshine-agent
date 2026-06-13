package com.sunshine.orchestrator.agent;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeEventMapperTest {

    @Test
    void summarizeHits_nullBlock_returnsZeroHits() {
        assertThat(AgentScopeEventMapper.summarizeHits((ToolResultBlock) null)).isEqualTo("命中 0 条");
    }

    @Test
    void summarizeHits_notFoundText_returnsZeroHits() {
        assertThat(AgentScopeEventMapper.summarizeHits(blockWithText("未找到相关知识库内容。")))
                .isEqualTo("命中 0 条");
    }

    @Test
    void summarizeHits_withHits_returnsCount() {
        String text = "知识库检索结果（共 3 条）：\n\n【文档片段 1】\n内容";
        assertThat(AgentScopeEventMapper.summarizeHits(blockWithText(text)))
                .isEqualTo("命中 3 条");
    }

    private static ToolResultBlock blockWithText(String text) {
        return ToolResultBlock.builder()
                .name("search_knowledge")
                .output(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
