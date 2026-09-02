package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * think = reasoning 通道：无 Thinking 内容不开步；ThinkingBlockEnd 即结掉，不等 tool_call。
 */
class TimelineSessionThinkFlowReasoningChannelTest {

    @BeforeEach
    void bindLabels() {
        TimelineLabelTestSupport.bindDefaults();
    }

    @AfterEach
    void unbindLabels() {
        TimelineLabelTestSupport.unbind();
    }

    @Test
    void beginReasoningRound_aloneDoesNotOpenThink() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("写计划");
        session.beginReasoningRound();

        assertThat(session.snapshot().stream().filter(s -> ThinkStepIds.isThinkStep(s.id())).count())
                .isZero();
        assertThat(session.isThinkRunning()).isFalse();
    }

    @Test
    void ensureThinkOpen_materializesThinkAfterPrepare() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("写计划");
        session.beginReasoningRound();
        session.ensureThinkOpen();

        assertThat(session.isThinkRunning()).isTrue();
        assertThat(session.currentThinkStepId()).isEqualTo("think");
        ProcessingStep think = session.snapshot().stream()
                .filter(s -> "think".equals(s.id())).findFirst().orElseThrow();
        assertThat(think.lifecycle()).isEqualTo("running");
    }

    @Test
    void endReasoningRound_afterThinkingCompletesThinkBeforeTools() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("写计划");
        session.beginReasoningRound();
        session.ensureThinkOpen();
        session.appendDelta("think", "reasoning", "I need to provide both path and content.");
        // 模拟 ThinkingBlockEnd：reasoning 通道结束即结掉
        session.endReasoningRound();

        ProcessingStep think = session.snapshot().stream()
                .filter(s -> "think".equals(s.id())).findFirst().orElseThrow();
        assertThat(think.lifecycle()).isEqualTo("done");
        assertThat(think.reasoning()).isEqualTo("I need to provide both path and content.");
        assertThat(session.isThinkRunning()).isFalse();
    }

    @Test
    void noThinking_roundProducesNoThinkStep() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("直接调工具");
        session.beginReasoningRound();
        // 本轮无 ThinkingBlock，仅 tool_call → 不应冒出空「深度思考」
        session.endReasoningRound();

        assertThat(session.snapshot().stream().filter(s -> ThinkStepIds.isThinkStep(s.id())).count())
                .isZero();
    }
}
