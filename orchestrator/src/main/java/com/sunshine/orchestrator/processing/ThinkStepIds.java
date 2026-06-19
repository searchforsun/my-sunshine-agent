package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.routing.ExecutionMode;

/**
 * ReAct 每轮推理对应独立 think 步骤：{@code think}、{@code think-2}、…
 */
public final class ThinkStepIds {

    private ThinkStepIds() {
    }

    public static boolean isThinkStep(String stepId) {
        return "think".equals(stepId)
                || (stepId != null && stepId.startsWith("think-"));
    }

    /** 第 n 轮（从 1 起）对应的步骤 id */
    public static String forIteration(int iteration) {
        if (iteration <= 1) {
            return "think";
        }
        return "think-" + iteration;
    }

    /** 从 stepId 解析轮次，无法解析时返回 1 */
    public static int iterationOf(String stepId) {
        if ("think".equals(stepId)) {
            return 1;
        }
        if (stepId != null && stepId.startsWith("think-")) {
            try {
                return Integer.parseInt(stepId.substring("think-".length()));
            } catch (NumberFormatException ignored) {
                return 2;
            }
        }
        return 1;
    }

    /** 时间线展示用中文标题：simple-llm 为构思作答，ReAct 为规划/综合分析 */
    public static String displayLabel(String stepId) {
        return displayLabel(stepId, ExecutionMode.REACT);
    }

    public static String displayLabel(String stepId, ExecutionMode mode) {
        if (mode == ExecutionMode.SIMPLE_LLM) {
            return iterationOf(stepId) <= 1 ? "构思回答" : "整理作答";
        }
        return iterationOf(stepId) <= 1 ? "规划推理" : "综合分析";
    }
}

