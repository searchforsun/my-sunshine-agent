package com.sunshine.orchestrator.execution.loop;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.WorkflowNodeTimeline;
import com.sunshine.orchestrator.processing.StepSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * loop 框内 body 步骤 → 挂到 node-{loopId}.subSteps；主 Timeline 仅保留 loop 一步。
 * 多轮时子步 id 加 {@code i{n}-} 前缀，避免同节点多轮互相覆盖。
 */
public final class LoopBodyTimelineBridge {

    private final String loopStepId;
    private final String loopLabel;
    private final Set<String> bodyNodeIds;
    private final List<ProcessingStep> subSteps = new ArrayList<>();

    public LoopBodyTimelineBridge(String loopNodeId, String loopLabel, List<String> bodyNodeIds) {
        this.loopStepId = WorkflowNodeTimeline.stepId(loopNodeId);
        this.loopLabel = loopLabel != null && !loopLabel.isBlank() ? loopLabel.strip() : loopNodeId;
        this.bodyNodeIds = Set.copyOf(bodyNodeIds != null ? bodyNodeIds : List.of());
    }

    public boolean isBodyToken(StreamToken token) {
        if (token == null) {
            return false;
        }
        if (token.isStep() && token.step() != null) {
            return isBodyStepId(token.step().id());
        }
        if (token.isStepDelta()) {
            return isBodyStepId(token.stepId());
        }
        return false;
    }

    /** scopeNodeStepId / stepId 是否归属本 loop body（含 node- 前缀） */
    public boolean isBodyScopedStepId(String stepId) {
        return isBodyStepId(stepId);
    }

    public List<StreamToken> wrap(StreamToken token, int iteration) {
        if (token == null) {
            return List.of();
        }
        int iter = Math.max(1, iteration);
        if (token.isStep() && token.step() != null) {
            // upsert：mergeReasoning 已前缀合并，避免指数膨胀；
            // 终态 complete 无 subSteps 时 mergeSubSteps 保留已累积的 think/tool
            ProcessingStepMerger.upsert(subSteps, rewriteStep(token.step(), iter));
            return List.of(loopStepUpdate());
        }
        if (token.isStepDelta()) {
            String rewrittenId = rewriteStepId(token.stepId(), iter);
            ProcessingStepMerger.applyDelta(subSteps, rewrittenId, token.channel(), token.text());
            return List.of(loopStepUpdate());
        }
        return List.of();
    }

    public List<ProcessingStep> subSteps() {
        return List.copyOf(subSteps);
    }

    private boolean isBodyStepId(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return false;
        }
        String id = stepId.startsWith("node-") ? stepId.substring("node-".length()) : stepId;
        return bodyNodeIds.contains(id);
    }

    private static String rewriteStepId(String stepId, int iteration) {
        if (stepId == null) {
            return "i" + iteration + "-unknown";
        }
        return "i" + iteration + "-" + stepId;
    }

    private static ProcessingStep rewriteStep(ProcessingStep step, int iteration) {
        return new ProcessingStep(
                rewriteStepId(step.id(), iteration),
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
                step.subSteps(),
                step.stepSummary());
    }

    private StreamToken loopStepUpdate() {
        long ts = System.currentTimeMillis();
        ProcessingStep node = new ProcessingStep(
                loopStepId,
                "node",
                "running",
                new StepSummary(null, loopLabel, null),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ts,
                loopLabel,
                null,
                null,
                List.copyOf(subSteps),
                null);
        return StreamToken.step(node);
    }
}
