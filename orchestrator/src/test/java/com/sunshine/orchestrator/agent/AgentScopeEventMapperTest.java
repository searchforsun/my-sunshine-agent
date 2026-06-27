package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamDeltaNormalizer;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeEventMapperTest {

    @Test
    void reasoningEvent_thinkingOnly_isIgnored() {
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
    void reasoningEvent_textBlock_streamsAnswerContent() {
        Msg partial = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("你好").build()))
                .build();
        Msg full = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("你好，世界").build()))
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");
        AtomicBoolean generateStarted = new AtomicBoolean(false);

        List<StreamToken> first = AgentScopeEventMapper.map(
                new Event(EventType.REASONING, partial, false), session, generateStarted);
        List<StreamToken> second = AgentScopeEventMapper.map(
                new Event(EventType.REASONING, full, false), session, generateStarted);

        List<StreamToken> normalized = new ArrayList<>();
        StreamDeltaNormalizer.normalizeTokens(Flux.fromIterable(first).concatWith(Flux.fromIterable(second)))
                .subscribe(normalized::add);

        assertThat(generateStarted).isTrue();
        assertThat(normalized.stream().filter(StreamToken::isContent).map(StreamToken::text).toList())
                .containsExactly("你好", "，世界");
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
    void secondAnswerContent_completesRunningThinkWithoutReopeningGenerate() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("先查待办再审批");
        session.beginReasoningRound();
        session.endReasoningRound();
        session.beginReasoningRound();
        AtomicBoolean generateStarted = new AtomicBoolean(false);
        generateStarted.set(true);
        session.pending("generate", "generate");
        session.startAt("generate", "generate", System.currentTimeMillis());

        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("第二轮正文").build()))
                .build();

        List<StreamToken> tokens = AgentScopeEventMapper.map(
                new Event(EventType.AGENT_RESULT, msg, false),
                session, generateStarted);

        assertThat(generateStarted).isTrue();
        assertThat(tokens.stream().filter(t -> t.isStep() && "think-2".equals(t.step().id())
                && "done".equals(t.step().lifecycle()))).hasSize(1);
        assertThat(tokens.stream().filter(t -> t.isStep() && "generate".equals(t.step().id()))).isEmpty();
        assertThat(tokens.stream().anyMatch(t -> t.isContent() && "第二轮正文".equals(t.text()))).isTrue();
        assertThat(session.isThinkRunning()).isFalse();
    }

    @Test
    void agentResult_textBlock_emitsContentOnly() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("完整回答正文").build()))
                .build();

        List<StreamToken> tokens = new ArrayList<>();
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
