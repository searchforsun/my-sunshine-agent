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
    private String activeStepId;

    public String activeStepId() {
        return activeStepId;
    }

    public void appendDelta(String channel, String text) {
        if (activeStepId == null || channel == null || text == null || text.isEmpty()) {
            return;
        }
        long ts = System.currentTimeMillis();
        aggregator.appendDelta(activeStepId, channel, text, ts);
    }

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
        startAt(stepId, phase, System.currentTimeMillis());
    }

    public void startAt(String stepId, String phase, long startedAt) {
        if (stepId.equals(activeStepId)) {
            ProcessingStep current = aggregator.get(stepId).orElse(null);
            if (current != null && "running".equals(current.lifecycle())) {
                return;
            }
        } else {
            completeRunningActive(startedAt);
        }
        activeStepId = stepId;
        applyAt(stepId, phase, EventKind.START, resolveActive(stepId), null, startedAt);
    }

    private void completeRunningActive(long endedAt) {
        if (activeStepId == null) {
            return;
        }
        aggregator.get(activeStepId).ifPresent(step -> {
            if ("running".equals(step.lifecycle())) {
                completeAt(activeStepId, step.detail(), endedAt);
            }
        });
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
        if (stepId.equals(activeStepId)) {
            activeStepId = null;
        }
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

    /** Agent 推理并行展示为 think 步骤，不抢占 agent 的 activeStepId */
    public void openThinkParallel() {
        long ts = System.currentTimeMillis();
        if (!hasStep("think")) {
            applyAt("think", "think", EventKind.PENDING, resolveBefore("think"), null, ts);
        }
        aggregator.get("think").ifPresent(step -> {
            if ("pending".equals(step.lifecycle())) {
                applyAt("think", "think", EventKind.START, resolveActive("think"), null, ts);
            }
        });
    }

    public boolean isThinkRunning() {
        return aggregator.get("think")
                .map(step -> "running".equals(step.lifecycle()))
                .orElse(false);
    }

    public void completeThinkParallelAt(long endedAt) {
        if (isThinkRunning()) {
            completeAt("think", null, endedAt);
        }
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
                && Objects.equals(prev.reasoning(), next.reasoning())
                && Objects.equals(prev.output(), next.output())
                && Objects.equals(prev.result(), next.result())
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
