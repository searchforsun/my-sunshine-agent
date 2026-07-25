package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
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

    /**
     * 断点续传截断：仅保留到「最后一个完整 think」（done 且其后已有 tool/rag 步）。
     * AgentScope checkpoint 只存 message 历史，think-N 流式中途的半截 reasoning 不在历史里，
     * 无法从流式断点续传；故 think done 后紧跟 tool（已完整并驱动工具调用）才可作为续接锚点，
     * think 后仅 tasks 或无后续步（中断在该 think 流式中途）则需连同该 think 一并丢弃，让其重生成。
     */
    public static void truncateToLastCompleteThink(java.util.List<ProcessingStep> steps) {
        int anchorIdx = -1;
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (!isThinkStep(step.id()) || !"done".equals(step.lifecycle())) {
                continue;
            }
            boolean followedByTool = false;
            for (int j = i + 1; j < steps.size(); j++) {
                ProcessingStep next = steps.get(j);
                if (isThinkStep(next.id()) || TimelineStepId.TASKS.matches(next.id())) {
                    continue;
                }
                followedByTool = true;
                break;
            }
            if (followedByTool) {
                anchorIdx = i;
            }
        }
        if (anchorIdx < 0) {
            return;
        }
        while (steps.size() > anchorIdx + 1) {
            steps.remove(steps.size() - 1);
        }
    }

    /**
     * 最后一个「完整」think 轮次（done 且其后有 tool/rag 步），无则 0。
     * 与 {@link #truncateToLastCompleteThink} 同一锚点语义，供续跑 think 轮次基线使用。
     */
    public static int lastCompleteThinkIteration(java.util.List<ProcessingStep> steps) {
        int last = 0;
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (!isThinkStep(step.id()) || !"done".equals(step.lifecycle())) {
                continue;
            }
            boolean followedByTool = false;
            for (int j = i + 1; j < steps.size(); j++) {
                ProcessingStep next = steps.get(j);
                if (isThinkStep(next.id()) || TimelineStepId.TASKS.matches(next.id())) {
                    continue;
                }
                followedByTool = true;
                break;
            }
            if (followedByTool) {
                last = Math.max(last, iterationOf(step.id()));
            }
        }
        return last;
    }
}

