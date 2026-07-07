package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.routing.ExecutionMode;

/**
 * ReAct 每轮推理对应独立 think 步骤：{@code think}、{@code think-2}、…
 */
public final class ThinkStepIds {

    private ThinkStepIds() {
    }

    public static boolean isThinkStep(String stepId) {
        return TimelineStepId.THINK.matches(stepId)
                || (stepId != null && stepId.startsWith(TimelineStepId.THINK.id() + "-"));
    }

    /** 第 n 轮（从 1 起）对应的步骤 id */
    public static String forIteration(int iteration) {
        if (iteration <= 1) {
            return TimelineStepId.THINK.id();
        }
        return TimelineStepId.THINK.id() + "-" + iteration;
    }

    /** 从 stepId 解析轮次，无法解析时返回 1 */
    public static int iterationOf(String stepId) {
        if (TimelineStepId.THINK.matches(stepId)) {
            return 1;
        }
        if (stepId != null && stepId.startsWith(TimelineStepId.THINK.id() + "-")) {
            try {
                return Integer.parseInt(stepId.substring((TimelineStepId.THINK.id() + "-").length()));
            } catch (NumberFormatException ignored) {
                return 2;
            }
        }
        return 1;
    }

    /** 时间线展示用中文标题，SSOT 见 Nacos agent.timeline.steps.think */
    public static String displayLabel(String stepId) {
        return displayLabel(stepId, ExecutionMode.REACT);
    }

    public static String displayLabel(String stepId, ExecutionMode mode) {
        return ThinkStepLabels.label(stepId, mode);
    }
}

