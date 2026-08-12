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
     * <p>截断后必须把 {@code tasks} 与 awaiting decision 卡 append 回去，否则「继续生成」会丢掉
     * TaskBoard / 决策卡，用户感知为从头开始。
     */
    public static void truncateToLastCompleteThink(java.util.List<ProcessingStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        ProcessingStep tasksStep = findTasksStep(steps);
        ProcessingStep awaitingDecision =
                com.sunshine.orchestrator.agent.ProcessingStepLifecycleOps.findReactAwaitingDecisionStep(steps);
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
        appendIfMissing(steps, tasksStep);
        appendIfMissing(steps, awaitingDecision);
    }

    private static ProcessingStep findTasksStep(java.util.List<ProcessingStep> steps) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            ProcessingStep step = steps.get(i);
            if (step != null && TimelineStepId.TASKS.matches(step.id())) {
                return step;
            }
        }
        return null;
    }

    private static void appendIfMissing(java.util.List<ProcessingStep> steps, ProcessingStep keep) {
        if (keep == null || keep.id() == null) {
            return;
        }
        for (ProcessingStep step : steps) {
            if (step != null && keep.id().equals(step.id())) {
                return;
            }
        }
        steps.add(keep);
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

