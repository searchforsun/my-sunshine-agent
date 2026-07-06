package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.NodeRecoveryMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.plan.PendingInteraction;

import java.util.ArrayList;
import java.util.List;

/** 步骤暂停 / 续跑 / HITL 扫描 */
public final class ProcessingStepLifecycleOps {

    private static final ObjectMapper OM = new ObjectMapper();

    private ProcessingStepLifecycleOps() {
    }
    /** 取消生成时：将 running 的 workflow 节点（含子 Agent subSteps）标为 paused */
    public static void pauseRunningWorkflowNodes(List<ProcessingStep> steps) {
        pauseRunningWorkflowNodes(steps, null, null);
    }

    public static void pauseRunningWorkflowNodes(List<ProcessingStep> steps, String currentNodeId) {
        pauseRunningWorkflowNodes(steps, currentNodeId, null);
    }

    public static void pauseRunningWorkflowNodes(
            List<ProcessingStep> steps, String currentNodeId, String skipNodeId) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (step.id() == null || !step.id().startsWith("node-")) {
                continue;
            }
            List<ProcessingStep> subSteps = step.subSteps();
            if (subSteps != null && !subSteps.isEmpty()) {
                List<ProcessingStep> updatedSubs = new ArrayList<>(subSteps);
                pauseRunningInPlace(updatedSubs);
                if (!updatedSubs.equals(subSteps)) {
                    step = copyWithSubSteps(step, updatedSubs);
                }
            }
            if (isRunning(step) || isAwaitingInteractionStep(step)) {
                step = toPaused(step);
            }
            steps.set(i, step);
        }
        if (org.springframework.util.StringUtils.hasText(currentNodeId)) {
            pauseWorkflowNodeAt(steps, "node-" + currentNodeId.strip());
        }
    }

    /** ReAct 停止：think/tool/generate 等 running 步标 paused */
    public static void pauseRunningReactSteps(List<ProcessingStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (step.id() != null && step.id().startsWith("node-")) {
                continue;
            }
            String phase = step.phase();
            if (phase == null) {
                continue;
            }
            if (!isRunning(step) && !isAwaitingInteractionStep(step)) {
                continue;
            }
            // HITL/Recovery 等待中停止：保留 awaiting 元数据，续跑 re-await
            if (isAwaitingInteractionStep(step)) {
                continue;
            }
            if ("think".equals(phase) || "agent".equals(phase) || "generate".equals(phase)
                    || phase.startsWith("think") || phase.startsWith("tool")) {
                steps.set(i, toPaused(step));
            }
        }
    }

    /** ReAct 暂停续跑：仅保留意图识别步，从规划推理重新开始 */
    public static List<ProcessingStep> retainIntentStepsOnly(List<ProcessingStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .filter(s -> s != null && "intent".equals(s.id()))
                .toList();
    }

    /** ReAct 写工具 HITL 待确认步（暂停续跑须先 re-await，勿走 simple-llm 续写） */
    public static ProcessingStep findReactAwaitingHitlStep(List<ProcessingStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            ProcessingStep step = steps.get(i);
            if (step.id() == null || step.id().startsWith("node-")) {
                continue;
            }
            if (!com.sunshine.orchestrator.processing.ToolStepIds.isToolStep(step.id())) {
                continue;
            }
            if (isAwaitingInteractionStep(step)) {
                return step;
            }
        }
        return null;
    }

    /** 扫描 HITL/Recovery awaiting 步，供暂停落库 pendingInteraction */
    public static PendingInteraction findPendingInteraction(List<ProcessingStep> steps) {
        if (steps == null) {
            return null;
        }
        for (ProcessingStep step : steps) {
            if (step.id() == null || !step.id().startsWith("node-")) {
                continue;
            }
            StepMetadata meta = step.metadata();
            if (meta == null) {
                continue;
            }
            String nodeId = step.id().substring("node-".length());
            HitlStepMeta hitl = meta.hitl();
            if (hitl != null && HitlStepMeta.STATUS_AWAITING.equals(hitl.status())) {
                return new PendingInteraction(
                        "hitl", nodeId, null, "", hitl.paramsSummary(), null);
            }
            NodeRecoveryMeta recovery = meta.recovery();
            if (recovery != null && NodeRecoveryMeta.STATUS_AWAITING.equals(recovery.status())) {
                String attemptsJson = null;
                if (meta.nodeAttempts() != null && !meta.nodeAttempts().isEmpty()) {
                    try {
                        attemptsJson = OM.writeValueAsString(meta.nodeAttempts());
                    } catch (Exception ignored) {
                        attemptsJson = null;
                    }
                }
                return new PendingInteraction(
                        "recovery", nodeId, recovery.errorMessage(), null, null, attemptsJson);
            }
        }
        return null;
    }

    private static void pauseWorkflowNodeAt(List<ProcessingStep> steps, String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (!stepId.equals(step.id())) {
                continue;
            }
            if (!isRunning(step) && !isAwaitingInteractionStep(step)) {
                continue;
            }
            steps.set(i, toPaused(step));
            return;
        }
    }

    public static boolean isAwaitingInteractionStep(ProcessingStep step) {
        StepMetadata meta = step != null ? step.metadata() : null;
        if (meta == null) {
            return false;
        }
        if (meta.hitl() != null && HitlStepMeta.STATUS_AWAITING.equals(meta.hitl().status())) {
            return true;
        }
        return meta.recovery() != null && NodeRecoveryMeta.STATUS_AWAITING.equals(meta.recovery().status());
    }

    private static void pauseRunningInPlace(List<ProcessingStep> steps) {
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (isRunning(step) || isAwaitingInteractionStep(step)) {
                steps.set(i, toPaused(step));
            }
        }
    }

    private static ProcessingStep copyWithSubSteps(ProcessingStep step, List<ProcessingStep> subSteps) {
        return new ProcessingStep(
                step.id(),
                step.phase(),
                step.lifecycle(),
                step.summary(),
                step.startedAt(),
                step.endedAt(),
                step.durationMs(),
                step.detail(),
                step.reasoning(),
                step.output(),
                step.result(),
                step.ts(),
                step.label(),
                step.metadata(),
                step.contentBlocks(),
                subSteps);
    }

    public static String findLastRunningWorkflowNodeId(List<ProcessingStep> steps) {
        if (steps == null) {
            return null;
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            ProcessingStep step = steps.get(i);
            if (step.id() != null && step.id().startsWith("node-") && isRunning(step)) {
                return step.id().substring("node-".length());
            }
        }
        return null;
    }

    /** 是否存在 running 的 workflow 节点步 */
    public static boolean hasRunningWorkflowNode(List<ProcessingStep> steps) {
        return findLastRunningWorkflowNodeId(steps) != null;
    }

    private static boolean isRunning(ProcessingStep step) {
        return "running".equals(step.lifecycle());
    }

    private static ProcessingStep toPaused(ProcessingStep step) {
        StepSummary summary = step.summary();
        StepSummary pausedSummary = new StepSummary(
                summary != null ? summary.before() : null,
                "已暂停",
                "已暂停");
        return new ProcessingStep(
                step.id(),
                step.phase(),
                "paused",
                pausedSummary,
                step.startedAt(),
                System.currentTimeMillis(),
                step.startedAt() != null ? System.currentTimeMillis() - step.startedAt() : step.durationMs(),
                step.detail(),
                step.reasoning(),
                step.output(),
                step.result(),
                System.currentTimeMillis(),
                step.label(),
                step.metadata(),
                step.contentBlocks(),
                step.subSteps());
    }
}
