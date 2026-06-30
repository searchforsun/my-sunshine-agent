package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** 步骤事件下发：aggregator 写入 + SSE listener */
final class TimelineSessionEmitter {

    private final TimelineSessionState state;

    TimelineSessionEmitter(TimelineSessionState state) {
        this.state = state;
    }

    void onStepChanged(Consumer<ProcessingStep> listener) {
        state.onStepChanged = listener != null ? listener : s -> {};
    }

    void addStepListener(Consumer<ProcessingStep> listener) {
        if (listener == null) {
            return;
        }
        Consumer<ProcessingStep> prev = state.onStepChanged;
        state.onStepChanged = s -> {
            prev.accept(s);
            listener.accept(s);
        };
    }

    Consumer<ProcessingStep> currentListener() {
        return state.onStepChanged;
    }

    boolean hasStep(String stepId) {
        return state.aggregator.get(stepId).isPresent();
    }

    List<ProcessingStep> snapshot() {
        return state.aggregator.snapshot();
    }

    Optional<ProcessingStep> lastChanged() {
        return Optional.ofNullable(state.lastEmitted);
    }

    void appendDelta(String stepId, String channel, String text) {
        if (stepId == null || channel == null || text == null || text.isEmpty()) {
            return;
        }
        state.aggregator.appendDelta(stepId, channel, text, System.currentTimeMillis());
    }

    void bindStepDisplayName(String stepId, String displayName) {
        if (stepId == null || displayName == null || displayName.isBlank()) {
            return;
        }
        String name = displayName.strip();
        state.stepDisplayNames.put(stepId, name);
        state.aggregator.bindLabel(stepId, name);
    }

    void apply(String stepId, String phase, EventKind kind, String summary, String detail) {
        applyAt(stepId, phase, kind, summary, detail, null, System.currentTimeMillis());
    }

    void applyAt(String stepId, String phase, EventKind kind, String summary, String detail, long ts) {
        applyAt(stepId, phase, kind, summary, detail, null, ts);
    }

    void applyAt(
            String stepId, String phase, EventKind kind, String summary, String detail,
            StepMetadata metadata, long ts) {
        ProcessingStep prev = state.aggregator.get(stepId).orElse(null);
        String effectivePhase = phase != null ? phase : prev != null ? prev.phase() : stepId;
        state.aggregator.apply(new ProcessingEvent(stepId, effectivePhase, kind, summary, ts, detail, metadata));
        ProcessingStep next = state.aggregator.get(stepId).orElseThrow();
        if (!sameState(prev, next) || isTerminalEvent(kind)) {
            state.lastEmitted = next;
            state.onStepChanged.accept(next);
        }
    }

    boolean isStepRunning(String stepId) {
        return state.aggregator.get(stepId)
                .map(step -> "running".equals(step.lifecycle()))
                .orElse(false);
    }

    private static boolean isTerminalEvent(EventKind kind) {
        return kind == EventKind.COMPLETE || kind == EventKind.FAIL || kind == EventKind.SKIP
                || kind == EventKind.PAUSE || kind == EventKind.TERMINATE;
    }

    private static boolean sameState(ProcessingStep prev, ProcessingStep next) {
        if (prev == null) {
            return false;
        }
        return Objects.equals(prev.id(), next.id())
                && Objects.equals(prev.phase(), next.phase())
                && Objects.equals(prev.lifecycle(), next.lifecycle())
                && summaryEquals(prev.summary(), next.summary())
                && Objects.equals(prev.startedAt(), next.startedAt())
                && Objects.equals(prev.endedAt(), next.endedAt())
                && Objects.equals(prev.durationMs(), next.durationMs())
                && Objects.equals(prev.detail(), next.detail())
                && Objects.equals(prev.reasoning(), next.reasoning())
                && Objects.equals(prev.output(), next.output())
                && Objects.equals(prev.result(), next.result())
                && Objects.equals(prev.label(), next.label())
                && Objects.equals(prev.metadata(), next.metadata());
    }

    private static boolean summaryEquals(StepSummary a, StepSummary b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.before(), b.before())
                && Objects.equals(a.active(), b.active())
                && Objects.equals(a.after(), b.after());
    }
}
