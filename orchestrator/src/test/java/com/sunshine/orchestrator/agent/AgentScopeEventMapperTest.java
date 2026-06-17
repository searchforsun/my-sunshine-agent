package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeEventMapperTest {

    @Test
    void reasoningEvent_emptyMessage_emitsThinkStep() {
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).build();
        Event event = new Event(EventType.REASONING, msg, true);

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("有哪些审批财务消息");

        List<StreamToken> tokens = AgentScopeEventMapper.map(
                event,
                session,
                new AtomicBoolean(false),
                null,
                "有哪些审批财务消息");

        assertThat(tokens.stream().anyMatch(t -> t.isStep() && "think".equals(t.step().id()))).isTrue();
        assertThat(tokens.stream().noneMatch(t -> t.isStep() && "agent".equals(t.step().id()))).isTrue();
    }

    @Test
    void reasoningEvent_thinkingBlock_emitsThinkStepDelta() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(ThinkingBlock.builder().thinking("内部分析步骤").build()))
                .build();

        List<StreamToken> tokens = new java.util.ArrayList<>();
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试问题");
        AgentScopeEventMapper.appendThinkingTokens(msg, session, tokens);

        assertThat(tokens.stream().anyMatch(t -> t.isStepDelta()
                && "think".equals(t.stepId())
                && "reasoning".equals(t.channel())
                && "内部分析步骤".equals(t.text()))).isTrue();
        assertThat(tokens.stream().noneMatch(t -> t.isStep() && "agent".equals(t.step().id()))).isTrue();
    }

    @Test
    void reasoningEvent_thinkingBlock_fallbackToReasoningWithoutActiveStep() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(ThinkingBlock.builder().thinking("内部分析步骤").build()))
                .build();

        Event event = new Event(EventType.REASONING, msg, true);
        List<StreamToken> tokens = AgentScopeEventMapper.map(
                event, new ProcessingTimelineSession(), new AtomicBoolean(false), null, "q");

        assertThat(tokens.stream().anyMatch(t -> t.isStepDelta()
                && "think".equals(t.stepId())
                && "内部分析步骤".equals(t.text()))).isTrue();
    }

    @Test
    void agentResult_textBlock_emitsContentOnly() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("完整回答正文").build()))
                .build();

        List<StreamToken> tokens = new java.util.ArrayList<>();
        AgentScopeEventMapper.appendContentTokens(msg, tokens);

        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).isContent()).isTrue();
        assertThat(tokens.get(0).text()).isEqualTo("完整回答正文");
    }

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
        String text = """
                知识库检索结果（共 3 条）：
                来源文档：公司请假流程规范

                【公司请假流程规范 | 片段 1】
                内容""";
        assertThat(AgentScopeEventMapper.summarizeHits(blockWithText(text)))
                .isEqualTo("命中 3 条，来源：公司请假流程规范");
    }

    private static ToolResultBlock blockWithText(String text) {
        return ToolResultBlock.builder()
                .name("search_knowledge")
                .output(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
