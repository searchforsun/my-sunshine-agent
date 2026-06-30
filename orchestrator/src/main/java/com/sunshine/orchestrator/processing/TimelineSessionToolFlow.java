package com.sunshine.orchestrator.processing;

import java.util.List;

/** ReAct 工具步骤 + HITL / 节点 recovery metadata */
final class TimelineSessionToolFlow {

    private final TimelineSessionState state;
    private final TimelineSessionEmitter emitter;
    private final TimelineSessionLifecycle lifecycle;

    TimelineSessionToolFlow(
            TimelineSessionState state,
            TimelineSessionEmitter emitter,
            TimelineSessionLifecycle lifecycle) {
        this.state = state;
        this.emitter = emitter;
        this.lifecycle = lifecycle;
    }

    String beginToolStep(String baseStepId, String phase) {
        long startedAt = System.currentTimeMillis();
        String stepId = uniqueToolStepId(baseStepId, startedAt);
        state.currentToolStepId = stepId;
        long invokeAt = ToolStepIds.invokeEpochMs(stepId).orElse(startedAt);
        lifecycle.pending(stepId, phase);
        lifecycle.startAt(stepId, phase, invokeAt);
        return stepId;
    }

    void completeToolStep(String detail) {
        if (state.currentToolStepId == null) {
            return;
        }
        lifecycle.completeAt(state.currentToolStepId, detail, System.currentTimeMillis());
        state.currentToolStepId = null;
    }

    void skipCurrentToolStep(String afterSummary) {
        if (state.currentToolStepId == null) {
            return;
        }
        lifecycle.skip(state.currentToolStepId, afterSummary);
        state.currentToolStepId = null;
    }

    void progressCurrentToolStep(String activeSummary) {
        if (state.currentToolStepId == null || activeSummary == null || activeSummary.isBlank()) {
            return;
        }
        lifecycle.progress(state.currentToolStepId, activeSummary);
    }

    String currentToolStepId() {
        return state.currentToolStepId;
    }

    void attachHitlPending(String token, String toolDisplayName, String paramsSummary, long expiresAt) {
        if (state.currentToolStepId == null || token == null || token.isBlank()) {
            return;
        }
        attachHitlPendingOnStep(state.currentToolStepId, token, toolDisplayName, paramsSummary, expiresAt);
    }

    void resolveHitlPending(String status) {
        if (state.currentToolStepId == null || status == null) {
            return;
        }
        resolveHitlPendingOnStep(state.currentToolStepId, status);
    }

    void attachHitlPendingOnStep(
            String stepId, String token, String toolDisplayName, String paramsSummary, long expiresAt) {
        if (stepId == null || token == null || token.isBlank()) {
            return;
        }
        StepMetadata base = state.aggregator.get(stepId).map(com.sunshine.orchestrator.agent.ProcessingStep::metadata).orElse(null);
        StepMetadata meta = StepMetadata.withHitl(
                base, HitlStepMeta.awaiting(token, toolDisplayName, paramsSummary, expiresAt));
        emitter.applyAt(stepId, null, EventKind.PROGRESS, null, null, meta, System.currentTimeMillis());
    }

    void resolveHitlPendingOnStep(String stepId, String status) {
        if (stepId == null || status == null) {
            return;
        }
        StepMetadata base = state.aggregator.get(stepId).map(com.sunshine.orchestrator.agent.ProcessingStep::metadata).orElse(null);
        if (base == null || base.hitl() == null) {
            return;
        }
        StepMetadata meta = StepMetadata.withHitl(base, HitlStepMeta.resolved(status, base.hitl()));
        emitter.applyAt(stepId, null, EventKind.PROGRESS, null, null, meta, System.currentTimeMillis());
    }

    void attachNodeRecoveryOnStep(String stepId, String token, String errorMessage, long expiresAt) {
        if (stepId == null || token == null || token.isBlank()) {
            return;
        }
        StepMetadata base = state.aggregator.get(stepId).map(com.sunshine.orchestrator.agent.ProcessingStep::metadata).orElse(null);
        StepMetadata meta = StepMetadata.withRecovery(
                base, NodeRecoveryMeta.awaiting(token, errorMessage, expiresAt));
        emitter.applyAt(stepId, null, EventKind.PROGRESS, null, null, meta, System.currentTimeMillis());
    }

    void resolveNodeRecoveryOnStep(String stepId, String status) {
        if (stepId == null || status == null) {
            return;
        }
        StepMetadata base = state.aggregator.get(stepId).map(com.sunshine.orchestrator.agent.ProcessingStep::metadata).orElse(null);
        if (base == null || base.recovery() == null) {
            return;
        }
        StepMetadata meta = StepMetadata.withRecovery(base, NodeRecoveryMeta.resolved(status, base.recovery()));
        emitter.applyAt(stepId, null, EventKind.PROGRESS, null, null, meta, System.currentTimeMillis());
    }

    void updateNodeAttemptsOnStep(String stepId, List<NodeAttemptMeta> attempts) {
        if (stepId == null || attempts == null || attempts.isEmpty()) {
            return;
        }
        StepMetadata base = state.aggregator.get(stepId).map(com.sunshine.orchestrator.agent.ProcessingStep::metadata).orElse(null);
        StepMetadata meta = StepMetadata.withNodeAttempts(base, attempts);
        emitter.applyAt(stepId, null, EventKind.PROGRESS, null, null, meta, System.currentTimeMillis());
    }

    void noteToolCallPending() {
        state.pendingToolCalls++;
    }

    void noteToolCallDone() {
        if (state.pendingToolCalls > 0) {
            state.pendingToolCalls--;
        }
    }

    boolean hasPendingToolCalls() {
        return state.pendingToolCalls > 0;
    }

    private String uniqueToolStepId(String baseStepId, long startedAt) {
        long candidate = startedAt;
        for (int i = 0; i < 1000; i++) {
            String stepId = ToolStepIds.forInvocation(baseStepId, candidate);
            if (!state.aggregator.get(stepId).isPresent()) {
                return stepId;
            }
            candidate++;
        }
        return ToolStepIds.forInvocation(baseStepId, System.nanoTime());
    }
}
