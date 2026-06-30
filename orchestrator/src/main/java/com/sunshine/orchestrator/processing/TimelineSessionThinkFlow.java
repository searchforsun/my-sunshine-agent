package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;

/** ReAct think 轮次 + 正文段锚点 */
final class TimelineSessionThinkFlow {

    private final TimelineSessionState state;
    private final TimelineSessionEmitter emitter;
    private final TimelineSessionLifecycle lifecycle;

    TimelineSessionThinkFlow(
            TimelineSessionState state,
            TimelineSessionEmitter emitter,
            TimelineSessionLifecycle lifecycle) {
        this.state = state;
        this.emitter = emitter;
        this.lifecycle = lifecycle;
    }

    String currentThinkStepId() {
        return state.currentThinkId;
    }

    String openNextThink() {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            return state.currentThinkId;
        }
        state.thinkIteration++;
        state.currentThinkId = ThinkStepIds.forIteration(state.thinkIteration);
        lifecycle.pending(state.currentThinkId, "think");
        lifecycle.start(state.currentThinkId, "think");
        return state.currentThinkId;
    }

    boolean isThinkRunning() {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            return true;
        }
        return emitter.snapshot().stream()
                .anyMatch(s -> ThinkStepIds.isThinkStep(s.id()) && emitter.isStepRunning(s.id()));
    }

    void beginReasoningRound(Runnable closeContentSegment) {
        closeContentSegment.run();
        if (isThinkRunning()) {
            completeThinkIfRunning();
        }
        openNextThink();
    }

    void endReasoningRound() {
        completeThinkIfRunning();
    }

    void ingestStreamingContentDelta(String delta, java.util.function.Consumer<com.sunshine.orchestrator.client.StreamToken> sink) {
        if (delta == null || delta.isBlank()) {
            return;
        }
        completeThinkIfRunning();
        String anchor = contentAnchorAfterStepId();
        if (anchor == null || anchor.isBlank()) {
            return;
        }
        state.contentSegments.ingest(delta, anchor, sink);
    }

    String contentSegmentBaseline() {
        return state.contentSegments.currentBaseline();
    }

    String contentAnchorAfterStepId() {
        String lastDoneThink = null;
        for (ProcessingStep step : emitter.snapshot()) {
            if (ThinkStepIds.isThinkStep(step.id()) && "done".equals(step.lifecycle())) {
                lastDoneThink = step.id();
            }
        }
        return lastDoneThink;
    }

    void completeThinkIfRunning() {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            lifecycle.complete(state.currentThinkId, null);
            return;
        }
        emitter.snapshot().stream()
                .filter(s -> ThinkStepIds.isThinkStep(s.id()) && emitter.isStepRunning(s.id()))
                .map(ProcessingStep::id)
                .findFirst()
                .ifPresent(id -> lifecycle.complete(id, null));
    }

    void completeThinkParallelAt(long endedAt) {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            lifecycle.completeAt(state.currentThinkId, null, endedAt);
            return;
        }
        emitter.snapshot().stream()
                .filter(s -> ThinkStepIds.isThinkStep(s.id()) && emitter.isStepRunning(s.id()))
                .map(ProcessingStep::id)
                .findFirst()
                .ifPresent(id -> lifecycle.completeAt(id, null, endedAt));
    }
}
