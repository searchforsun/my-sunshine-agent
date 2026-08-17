package com.sunshine.orchestrator.context;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
