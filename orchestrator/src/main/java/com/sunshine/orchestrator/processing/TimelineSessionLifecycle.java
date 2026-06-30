package com.sunshine.orchestrator.processing;

/** 通用步骤 lifecycle：pending / start / progress / complete / fail */
final class TimelineSessionLifecycle {

    private final TimelineSessionEmitter emitter;
    private final TimelineSessionSummaries summaries;
    private final TimelineSessionCompletions completions;

    TimelineSessionLifecycle(
            TimelineSessionState state,
            TimelineSessionEmitter emitter,
            TimelineSessionSummaries summaries,
            TimelineSessionCompletions completions) {
        this.emitter = emitter;
        this.summaries = summaries;
        this.completions = completions;
    }

    void pending(String stepId, String phase) {
        emitter.apply(stepId, phase, EventKind.PENDING, summaries.resolveBefore(stepId), null);
    }

    void start(String stepId, String phase) {
        completions.startAt(stepId, phase, System.currentTimeMillis());
    }

    void startAt(String stepId, String phase, long startedAt) {
        completions.startAt(stepId, phase, startedAt);
    }

    void progress(String stepId, String activeSummary) {
        emitter.apply(stepId, null, EventKind.PROGRESS, activeSummary, null);
    }

    void complete(String stepId, String detail) {
        completions.completeAt(stepId, detail, System.currentTimeMillis());
    }

    void completeAt(String stepId, String detail, long endedAt) {
        completions.completeAt(stepId, detail, endedAt);
    }

    void fail(String stepId, String detail) {
        emitter.apply(stepId, null, EventKind.FAIL, detail, detail);
    }

    void pause(String stepId, String detail) {
        emitter.apply(stepId, null, EventKind.PAUSE, "已暂停", detail);
    }

    void terminate(String stepId, String detail) {
        emitter.apply(stepId, null, EventKind.TERMINATE, "已终止", detail);
    }

    void skip(String stepId, String afterSummary) {
        emitter.apply(stepId, null, EventKind.SKIP, afterSummary, null);
    }
}
