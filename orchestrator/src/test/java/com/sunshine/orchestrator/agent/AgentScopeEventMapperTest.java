package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeEventMapperTest {

    @Test
    void reasoningEvent_isIgnored() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(io.agentscope.core.message.ThinkingBlock.builder().thinking("不应出现").build()))
                .build();
        Event event = new Event(EventType.REASONING, msg, true);

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("对比财务与 OA");
        session.beginReasoningRound();

        List<StreamToken> tokens = AgentScopeEventMapper.map(
                event, session, new AtomicBoolean(false));

        assertThat(tokens).isEmpty();
    }

    @Test
    void agentResult_textBlock_emitsContentAndOpensGenerate() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("完整回答正文").build()))
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");
        AtomicBoolean generateStarted = new AtomicBoolean(false);

        List<StreamToken> tokens = AgentScopeEventMapper.map(
                new Event(EventType.AGENT_RESULT, msg, false),
                session, generateStarted);

        assertThat(generateStarted).isTrue();
        assertThat(tokens.stream().anyMatch(StreamToken::isStep)).isTrue();
        assertThat(tokens.stream().anyMatch(t -> t.isContent() && "完整回答正文".equals(t.text()))).isTrue();
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
