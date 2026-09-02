package com.sunshine.orchestrator.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextGroupEstimatorTest {

    private final ContextGroupEstimator estimator = new ContextGroupEstimator(new TokenEstimator());

    @Test
    void estimateMessagesSumsTextBlocks() {
        Msg a = Msg.builder().role(MsgRole.USER).textContent("你好世界").build();
        Msg b = Msg.builder().role(MsgRole.ASSISTANT).textContent("hello world").build();
        int expected = estimator.estimateText("你好世界") + estimator.estimateText("hello world");

        assertThat(estimator.estimateMessages(List.of(a, b))).isEqualTo(expected);
    }

    @Test
    void estimateMessagesCoversToolUseAndResult() {
        // assistant 消息：text + tool_use + tool_result（含嵌套 text）
        Msg assistant = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        TextBlock.builder().text("正在检索").build(),
                        ToolUseBlock.builder().name("search_knowledge").input(Map.of("q", "青松假")).build(),
                        ToolResultBlock.builder()
                                .name("search_knowledge")
                                .output(TextBlock.builder().text("检索结果片段").build())
                                .build())
                .build();

        int total = estimator.estimateMessages(List.of(assistant));
        int textOnly = estimator.estimateText("正在检索");
        int allParts = estimator.estimateText("正在检索")
                + estimator.estimateText("search_knowledge") * 2
                + estimator.estimateText("检索结果片段");
        assertThat(total).isGreaterThan(textOnly);
        assertThat(total).isGreaterThan(allParts);
    }

    @Test
    void estimateToolsCoversNameDescriptionParameters() {
        ToolSchema tool = ToolSchema.builder()
                .name("search_knowledge")
                .description("检索知识库")
                .parameters(Map.of("type", "object"))
                .build();

        int expected = estimator.estimateText("search_knowledge")
                + estimator.estimateText("检索知识库")
                + estimator.estimateText("{\"type\":\"object\"}");

        assertThat(estimator.estimateTools(List.of(tool))).isEqualTo(expected);
    }

    @Test
    void estimateNullSafe() {
        assertThat(estimator.estimateText(null)).isZero();
        assertThat(estimator.estimateMessages(null)).isZero();
        assertThat(estimator.estimateTools(null)).isZero();
    }
}
