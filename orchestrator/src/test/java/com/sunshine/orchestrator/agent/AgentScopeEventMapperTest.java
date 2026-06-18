package com.sunshine.orchestrator.agent;



import com.sunshine.orchestrator.client.StreamToken;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ThinkStepIds;

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

    void reasoningEvent_emptyMessage_doesNotOpenThink() {

        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).build();

        Event event = new Event(EventType.REASONING, msg, true);



        ProcessingTimelineSession session = new ProcessingTimelineSession();

        session.bindUserQuery("有哪些审批财务消息");



        List<StreamToken> tokens = AgentScopeEventMapper.map(

                event, session, new AtomicBoolean(false), null, "有哪些审批财务消息");



        assertThat(tokens).isEmpty();

        assertThat(session.snapshot().stream().noneMatch(s -> "think".equals(s.id()))).isTrue();

    }



    @Test

    void reasoning_doesNotOpenOrCloseThinkOnIsLast() {

        ProcessingTimelineSession session = new ProcessingTimelineSession();

        session.bindUserQuery("对比财务与 OA");

        session.beginReasoningRound();



        Msg partial = Msg.builder()

                .role(MsgRole.ASSISTANT)

                .content(List.of(ThinkingBlock.builder().thinking("第一步").build()))

                .build();

        AgentScopeEventMapper.map(

                new Event(EventType.REASONING, partial, false),

                session, new AtomicBoolean(false), null, "对比财务与 OA");

        assertThat(session.isThinkRunning()).isTrue();



        Msg last = Msg.builder()

                .role(MsgRole.ASSISTANT)

                .content(List.of(ThinkingBlock.builder().thinking("第一步完成").build()))

                .build();

        AgentScopeEventMapper.map(

                new Event(EventType.REASONING, last, true),

                session, new AtomicBoolean(false), null, "对比财务与 OA");



        assertThat(session.isThinkRunning()).isTrue();

        assertThat(session.snapshot().stream().filter(s -> s.id().startsWith("think")).count()).isEqualTo(1);



        session.endReasoningRound();

        assertThat(session.isThinkRunning()).isFalse();

    }



    @Test

    void reasoning_withoutPreReasoning_doesNotEmit() {

        ProcessingTimelineSession session = new ProcessingTimelineSession();

        session.bindUserQuery("对比财务与 OA");



        Msg msg = Msg.builder()

                .role(MsgRole.ASSISTANT)

                .content(List.of(ThinkingBlock.builder().thinking("不应出现").build()))

                .build();



        List<StreamToken> tokens = AgentScopeEventMapper.map(

                new Event(EventType.REASONING, msg, true),

                session, new AtomicBoolean(false), null, "对比财务与 OA");



        assertThat(tokens).isEmpty();
        assertThat(session.snapshot().stream().noneMatch(s -> ThinkStepIds.isThinkStep(s.id()))).isTrue();
    }



    @Test

    void reasoningAfterPostReasoning_emitsDeltaOnly() {

        ProcessingTimelineSession session = new ProcessingTimelineSession();

        session.bindUserQuery("对比财务与 OA");



        session.beginReasoningRound();

        session.endReasoningRound();

        session.noteToolCallPending();

        session.noteToolCallDone();



        session.beginReasoningRound();



        Msg msg = Msg.builder()

                .role(MsgRole.ASSISTANT)

                .content(List.of(ThinkingBlock.builder().thinking("结合工具结果继续分析").build()))

                .build();



        List<StreamToken> tokens = AgentScopeEventMapper.map(

                new Event(EventType.REASONING, msg, true),

                session, new AtomicBoolean(false), null, "对比财务与 OA");



        assertThat(tokens.stream().anyMatch(t -> t.isStepDelta()

                && "think-2".equals(t.stepId())

                && "结合工具结果继续分析".equals(t.text()))).isTrue();

        assertThat(tokens.stream().noneMatch(StreamToken::isStep)).isTrue();

        assertThat(session.isThinkRunning()).isTrue();



        session.endReasoningRound();

        assertThat(session.snapshot().stream().filter(s -> "think-2".equals(s.id())).findFirst().orElseThrow().lifecycle())

                .isEqualTo("done");

    }



    @Test

    void appendThinkingTokens_textBlockOnly_doesNotEmitReasoning() {

        Msg msg = Msg.builder()

                .role(MsgRole.ASSISTANT)

                .content(List.of(TextBlock.builder().text("English planning in content").build()))

                .build();



        List<StreamToken> tokens = new java.util.ArrayList<>();

        ProcessingTimelineSession session = new ProcessingTimelineSession();

        AgentScopeEventMapper.appendThinkingTokens(msg, session, tokens);



        assertThat(tokens).isEmpty();

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

        session.beginReasoningRound();

        AgentScopeEventMapper.appendThinkingTokens(msg, session, tokens);



        assertThat(tokens.stream().anyMatch(t -> t.isStepDelta()

                && "think".equals(t.stepId())

                && "reasoning".equals(t.channel())

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

