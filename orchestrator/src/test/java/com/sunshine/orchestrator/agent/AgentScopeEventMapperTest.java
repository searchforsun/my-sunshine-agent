package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
    void reasoningEvent_textBlock_isIgnored_hookHandlesStreaming() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("你好").build()))
                .build();
        Event event = new Event(EventType.REASONING, msg, false);

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");
        session.beginReasoningRound();

        List<StreamToken> tokens = AgentScopeEventMapper.map(
                event, session, new AtomicBoolean(false));

        assertThat(tokens).isEmpty();
    }

    @Test
    void agentResult_emitsSegmentWhenNoPriorIncremental() {
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("完整回答正文").build()))
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");
        session.beginReasoningRound();
        session.endReasoningRound();
        AtomicBoolean answerContentStarted = new AtomicBoolean(false);

        List<StreamToken> tokens = AgentScopeEventMapper.map(
                new Event(EventType.AGENT_RESULT, msg, false),
                session, answerContentStarted);

        assertThat(answerContentStarted).isTrue();
        assertThat(tokens.stream().filter(StreamToken::isContentStart).count()).isEqualTo(1);
        assertThat(tokens.stream().filter(t -> t.isContent() && t.segmentId() != null).map(StreamToken::text).toList())
                .containsExactly("完整回答正文");
    }

    @Test
    void agentResult_extendsIncrementalWithoutFullReplay() {
        String report = "以下是完整的待办清单与合规分析。";
        Msg snapshot = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text(report).build()))
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");
        session.beginReasoningRound();
        session.endReasoningRound();
        AtomicBoolean answerContentStarted = new AtomicBoolean(false);

        ProcessingTimelineSupport.run(session, () -> session.ingestStreamingContentDelta("以下是"));
        session.drainAuxiliaryTokens();
        List<StreamToken> tail = AgentScopeEventMapper.map(
                new Event(EventType.AGENT_RESULT, snapshot, false), session, answerContentStarted);

        assertThat(tail.stream().filter(t -> t.isContent() && t.segmentId() != null).map(StreamToken::text).toList())
                .containsExactly("完整的待办清单与合规分析。");
    }

    @Test
    void agentResult_doesNotDuplicateAfterFullIncrementalStream() {
        String report = "## 待办调查\n\n---\n\n正文段落。";
        Msg doubled = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text(report + report).build()))
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");
        session.beginReasoningRound();
        session.endReasoningRound();
        AtomicBoolean answerContentStarted = new AtomicBoolean(false);

        ProcessingTimelineSupport.run(session, () -> session.ingestStreamingContentDelta(report));
        session.drainAuxiliaryTokens();
        List<StreamToken> tail = AgentScopeEventMapper.map(
                new Event(EventType.AGENT_RESULT, doubled, false), session, answerContentStarted);

        assertThat(tail.stream().noneMatch(t -> t.isContent() && t.segmentId() != null)).isTrue();
    }

    @Test
    void agentResult_skipsWhenBaselineMismatchAfterStreaming() {
        String streamed = "好的，我先同时拉取您的 OA待办和财务待办，看看有哪些事情等着处理。";
        String snapshot = "好的，我先同时拉取您的 OA 待办和财务待办，看看有哪些事情等着处理。";
        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text(snapshot).build()))
                .build();

        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");
        session.beginReasoningRound();
        session.endReasoningRound();
        AtomicBoolean answerContentStarted = new AtomicBoolean(false);

        ProcessingTimelineSupport.run(session, () -> session.ingestStreamingContentDelta(streamed));
        session.drainAuxiliaryTokens();
        List<StreamToken> tail = AgentScopeEventMapper.map(
                new Event(EventType.AGENT_RESULT, msg, false), session, answerContentStarted);

        assertThat(tail.stream().noneMatch(t -> t.isContent() && t.segmentId() != null)).isTrue();
    }

    @Test
    void secondAnswerContent_completesRunningThinkAndEmitsSegment() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("先查待办再审批");
        session.beginReasoningRound();
        session.endReasoningRound();
        session.beginReasoningRound();
        AtomicBoolean answerContentStarted = new AtomicBoolean(false);

        Msg msg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("第二轮正文").build()))
                .build();

        List<StreamToken> tokens = AgentScopeEventMapper.map(
                new Event(EventType.AGENT_RESULT, msg, false),
                session, answerContentStarted);

        assertThat(answerContentStarted).isTrue();
        assertThat(tokens.stream().filter(t -> t.isStep() && "think-2".equals(t.step().id())
                && "done".equals(t.step().lifecycle()))).hasSize(1);
        assertThat(tokens.stream().filter(t -> t.isContent() && t.segmentId() != null).map(StreamToken::text).toList())
                .containsExactly("第二轮正文");
        assertThat(session.isThinkRunning()).isFalse();
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
