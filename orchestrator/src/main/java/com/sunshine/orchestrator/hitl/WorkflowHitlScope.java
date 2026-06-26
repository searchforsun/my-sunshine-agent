package com.sunshine.orchestrator.hitl;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;

/** Workflow tool 节点 HITL 绑定 — 经 ExecutionStreamContext 传递 */
public final class WorkflowHitlScope {

    public record Binding(
            ProcessingTimelineSession session,
            String nodeStepId,
            String generationMessageId) {
    }

    private WorkflowHitlScope() {
    }
}
