package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断点续传「最后一个完整 think」锚点：
 * 仅 think done 且其后有 tool/rag 步才算完整（中断在 tool 阶段，可续接）；
 * think 后仅 tasks 或无后续步（中断在 think 流式中途）则不算，需回退重生成。
 */
class ThinkStepIdsTest {

    @Test
    void lastCompleteThinkIteration_toolInterrupt_keepsLastDoneThink() {
        // think done → tool done → think-2 done → tool running(中断在 tool)：锚点 think-2
        List<ProcessingStep> steps = new ArrayList<>(List.of(
                doneStep("intent"),
                doneStep("think"),
                doneStep("tool-a"),
                doneStep("think-2"),
                runningStep("tool-b")));
        assertThat(ThinkStepIds.lastCompleteThinkIteration(steps)).isEqualTo(2);

        ThinkStepIds.truncateToLastCompleteThink(steps);
        assertThat(steps).extracting(ProcessingStep::id)
                .containsExactly("intent", "think", "tool-a", "think-2");
    }

    @Test
    void lastCompleteThinkIteration_thinkMidStream_rollsBackToPrevThink() {
        // think done → tool done → think-2 done(流式中途误标) → tasks(paused)：think-2 后仅 tasks，回退到 think
        List<ProcessingStep> steps = new ArrayList<>(List.of(
                doneStep("intent"),
                doneStep("think"),
                doneStep("tool-a"),
                doneStep("think-2"),
                pausedStep("tasks")));
        assertThat(ThinkStepIds.lastCompleteThinkIteration(steps)).isEqualTo(1);

        ThinkStepIds.truncateToLastCompleteThink(steps);
        assertThat(steps).extracting(ProcessingStep::id)
                .containsExactly("intent", "think");
    }

    @Test
    void lastCompleteThinkIteration_thinkIsLastStep_rollsBack() {
        // think done → tool done → think-2 done(最后一步，中断在其流式中途)：回退到 think
        List<ProcessingStep> steps = new ArrayList<>(List.of(
                doneStep("intent"),
                doneStep("think"),
                doneStep("tool-a"),
                doneStep("think-2")));
        assertThat(ThinkStepIds.lastCompleteThinkIteration(steps)).isEqualTo(1);
    }

    @Test
    void lastCompleteThinkIteration_noCompleteThink_returnsZero() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(
                doneStep("intent"),
                runningStep("think")));
        assertThat(ThinkStepIds.lastCompleteThinkIteration(steps)).isEqualTo(0);

        ThinkStepIds.truncateToLastCompleteThink(steps);
        // 无完整 think 锚点：不截断
        assertThat(steps).hasSize(2);
    }

    private static ProcessingStep doneStep(String id) {
        return new ProcessingStep(
                id, id, "done",
                new StepSummary("before", "active", "after"),
                1L, 2L, 1L, null, null, null, null,
                System.currentTimeMillis(), id, null, null, null);
    }

    private static ProcessingStep runningStep(String id) {
        return new ProcessingStep(
                id, id, "running",
                new StepSummary("before", "active", null),
                1L, null, null, null, null, null, null,
                System.currentTimeMillis(), id, null, null, null);
    }

    private static ProcessingStep pausedStep(String id) {
        return new ProcessingStep(
                id, id, "paused",
                new StepSummary("before", "已暂停", "已暂停"),
                1L, 2L, 1L, null, null, null, null,
                System.currentTimeMillis(), id, null, null, null);
    }
}
