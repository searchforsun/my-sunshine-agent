package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class ProcessingTimelineSession {

    private final TimelineAggregator aggregator = new TimelineAggregator();
    private Consumer<ProcessingStep> onStepChanged = s -> {};
    private ProcessingStep lastEmitted;
    private String userQuery;

    public void bindUserQuery(String query) {
        if (query != null && !query.isBlank()) {
            this.userQuery = query.strip();
        }
    }

    public String userQuery() {
        return userQuery;
    }

    public void onStepChanged(Consumer<ProcessingStep> listener) {
        this.onStepChanged = listener != null ? listener : s -> {};
    }

    /** 追加监听，不覆盖已有 listener（供 Hook 异步步骤使用） */
    public void addStepListener(Consumer<ProcessingStep> listener) {
        if (listener == null) {
            return;
        }
        Consumer<ProcessingStep> prev = this.onStepChanged;
        this.onStepChanged = s -> {
            prev.accept(s);
            listener.accept(s);
        };
    }

    Consumer<ProcessingStep> currentListener() {
        return onStepChanged;
    }

    public boolean hasStep(String stepId) {
        return aggregator.get(stepId).isPresent();
    }

    public void pending(String stepId, String phase) {
        apply(stepId, phase, EventKind.PENDING, resolveBefore(stepId), null);
    }

    public void start(String stepId, String phase) {
        apply(stepId, phase, EventKind.START, resolveActive(stepId), null);
    }

    public void progress(String stepId, String activeSummary) {
        apply(stepId, null, EventKind.PROGRESS, activeSummary, null);
    }

    public void complete(String stepId, String detail) {
        completeAt(stepId, detail, System.currentTimeMillis());
    }

    public void completeAt(String stepId, String detail, long endedAt) {
        String after = resolveAfter(stepId, detail);
        applyAt(stepId, null, EventKind.COMPLETE, after, detail, endedAt);
    }

    private String resolveBefore(String stepId) {
        return userQuery != null
                ? StepSummarizer.before(stepId, userQuery)
                : StepSummarizer.beforeFallback(stepId);
    }

    private String resolveActive(String stepId) {
        return userQuery != null
                ? StepSummarizer.active(stepId, userQuery)
                : StepSummarizer.activeFallback(stepId);
    }

    private String resolveAfter(String stepId, String detail) {
        return userQuery != null
                ? StepSummarizer.after(stepId, userQuery, detail)
                : StepSummarizer.afterFallback(stepId, detail);
    }

    public void fail(String stepId, String detail) {
        apply(stepId, null, EventKind.FAIL, detail, detail);
    }

    public void skip(String stepId, String afterSummary) {
        apply(stepId, null, EventKind.SKIP, afterSummary, null);
    }

    public List<ProcessingStep> snapshot() {
        return aggregator.snapshot();
    }

    public Optional<ProcessingStep> lastChanged() {
        return Optional.ofNullable(lastEmitted);
    }

    private void apply(String stepId, String phase, EventKind kind, String summary, String detail) {
        applyAt(stepId, phase, kind, summary, detail, System.currentTimeMillis());
    }

    private void applyAt(String stepId, String phase, EventKind kind, String summary, String detail, long ts) {
        ProcessingStep prev = aggregator.get(stepId).orElse(null);
        String effectivePhase = phase != null ? phase : prev != null ? prev.phase() : stepId;
        aggregator.apply(new ProcessingEvent(stepId, effectivePhase, kind, summary, ts, detail));
        ProcessingStep next = aggregator.get(stepId).orElseThrow();
        if (!sameState(prev, next)) {
            lastEmitted = next;
            onStepChanged.accept(next);
        }
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
                && Objects.equals(prev.status(), next.status())
                && Objects.equals(prev.label(), next.label());
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
