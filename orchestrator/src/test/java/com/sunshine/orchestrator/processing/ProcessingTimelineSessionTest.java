package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingTimelineSessionTest {

    @Test
    void emitsOnPendingStartComplete() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        List<ProcessingStep> emitted = new ArrayList<>();
        session.onStepChanged(emitted::add);

        session.bindUserQuery("年假政策");
        session.pending("intent", "intent");
        session.start("intent", "intent");
        session.complete("intent", "知识库查询");

        assertEquals(3, emitted.size());
        assertEquals("pending", emitted.get(0).lifecycle());
        assertEquals("running", emitted.get(1).lifecycle());
        assertEquals("done", emitted.get(2).lifecycle());
        assertThat(emitted.get(2).summary().after()).contains("年假政策");
        assertTrue(session.lastChanged().isPresent());
        assertEquals("done", session.lastChanged().get().lifecycle());
    }

    @Test
    void noteToolCallPending_onlyTracksPendingCount() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.openNextThink();
        session.noteToolCallPending();

        assertThat(session.hasPendingToolCalls()).isTrue();
        assertThat(session.isThinkRunning()).isTrue();
    }

    @Test
    void beginEndReasoningRound_closesRunningThink() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.beginReasoningRound();
        session.endReasoningRound();

        assertThat(session.snapshot().stream().filter(s -> "think".equals(s.id())).findFirst().orElseThrow().lifecycle())
                .isEqualTo("done");
        assertThat(session.isThinkRunning()).isFalse();
    }

    @Test
    void openNextThink_createsIncrementalIds() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("测试");

        assertThat(session.openNextThink()).isEqualTo("think");
        session.complete("think", null);

        assertThat(session.openNextThink()).isEqualTo("think-2");
    }

    @Test
    void openNextThink_reusesRunningStep() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.openNextThink();
        String first = session.currentThinkStepId();
        session.openNextThink();
        assertThat(session.currentThinkStepId()).isEqualTo(first);
        assertThat(session.snapshot()).hasSize(1);
    }

    @Test
    void threeToolReactTimeline_oneAnalysisPerTool() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();

        // 规划推理 → 工具1
        session.beginReasoningRound();
        session.endReasoningRound();
        session.noteToolCallPending();
        session.noteToolCallDone();

        // 工具1 返回后综合分析
        session.beginReasoningRound();
        session.endReasoningRound();
        assertThat(session.snapshot().stream().filter(s -> "think-2".equals(s.id())).findFirst().orElseThrow().lifecycle())
                .isEqualTo("done");

        // 综合分析 → 工具2
        session.noteToolCallPending();
        session.noteToolCallDone();

        // 工具2 返回后综合分析
        session.beginReasoningRound();
        session.endReasoningRound();
        assertThat(session.snapshot().stream().filter(s -> "think-3".equals(s.id())).findFirst().orElseThrow().lifecycle())
                .isEqualTo("done");

        // 综合分析 → 工具3
        session.noteToolCallPending();
        session.noteToolCallDone();

        // 最后一轮综合分析
        session.beginReasoningRound();
        session.endReasoningRound();

        long analysisCount = session.snapshot().stream()
                .filter(s -> s.id().startsWith("think-") && "done".equals(s.lifecycle()))
                .count();
        assertThat(analysisCount).isEqualTo(3);
    }

    @Test
    void doesNotEmitDuplicateOnSameState() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        List<ProcessingStep> emitted = new ArrayList<>();
        session.onStepChanged(emitted::add);

        session.start("intent", "intent");
        session.start("intent", "intent");
        session.progress("intent", "正在匹配处理方式");
        session.progress("intent", "正在匹配处理方式");

        assertEquals(1, emitted.size());
        assertEquals("running", emitted.get(0).lifecycle());
    }
}
