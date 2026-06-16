package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineAggregatorDeltaTest {

    @Test
    void startThenReasoningDeltasThenComplete_hasReasoningAndDuration() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("考勤制度是什么？");

        session.pending("agent", "agent");
        session.startAt("agent", "agent", 1_000L);
        session.appendDelta("reasoning", "分析");
        session.appendDelta("reasoning", "用户");
        session.appendDelta("reasoning", "问题");
        session.completeAt("agent", "推理完成", 1_300L);

        ProcessingStep step = session.snapshot().stream()
                .filter(s -> "agent".equals(s.id()))
                .findFirst()
                .orElseThrow();

        assertThat(step.lifecycle()).isEqualTo("done");
        assertThat(step.reasoning()).isEqualTo("分析用户问题");
        assertThat(step.startedAt()).isEqualTo(1_000L);
        assertThat(step.endedAt()).isEqualTo(1_300L);
        assertThat(step.durationMs()).isEqualTo(300L);
    }

    @Test
    void appendDeltaDirectly_createsRunningStepWithText() {
        TimelineAggregator aggregator = new TimelineAggregator();
        aggregator.appendDelta("rag", "output", "检索中", 100L);
        aggregator.appendDelta("rag", "output", "…", 110L);

        ProcessingStep step = aggregator.get("rag").orElseThrow();
        assertThat(step.lifecycle()).isEqualTo("running");
        assertThat(step.output()).isEqualTo("检索中…");
        assertThat(step.startedAt()).isEqualTo(100L);
    }
}
