package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TimelineAggregator {

    private final Map<String, StepState> steps = new LinkedHashMap<>();

    public void apply(ProcessingEvent event) {
        StepState state = steps.computeIfAbsent(event.stepId(), id -> new StepState(id));
        state.phase = event.phase();
        state.ts = event.ts();

        switch (event.kind()) {
            case PENDING -> {
                state.lifecycle = "pending";
                state.before = event.summary();
            }
            case START -> {
                state.lifecycle = "running";
                if (state.startedAt == null || event.ts() < state.startedAt) {
                    state.startedAt = event.ts();
                }
                state.active = event.summary();
            }
            case PROGRESS -> {
                state.active = event.summary();
            }
            case COMPLETE -> {
                state.lifecycle = "done";
                finish(state, event);
            }
            case FAIL -> {
                state.lifecycle = "error";
                finish(state, event);
            }
            case SKIP -> {
                state.lifecycle = "skipped";
                state.after = event.summary();
                if (event.detail() != null) {
                    state.detail = event.detail();
                }
            }
        }
    }

    public List<ProcessingStep> snapshot() {
        List<ProcessingStep> result = new ArrayList<>(steps.size());
        for (StepState state : steps.values()) {
            result.add(state.toSnapshot());
        }
        return result;
    }

    public Optional<ProcessingStep> get(String stepId) {
        StepState state = steps.get(stepId);
        return state == null ? Optional.empty() : Optional.of(state.toSnapshot());
    }

    private static void finish(StepState state, ProcessingEvent event) {
        state.endedAt = event.ts();
        if (state.startedAt != null) {
            state.durationMs = state.endedAt - state.startedAt;
        }
        state.after = event.summary();
        if (event.detail() != null) {
            state.detail = event.detail();
        }
    }

    private static final class StepState {
        private final String id;
        private String phase;
        private String lifecycle;
        private String before;
        private String active;
        private String after;
        private Long startedAt;
        private Long endedAt;
        private Long durationMs;
        private String detail;
        private long ts;

        private StepState(String id) {
            this.id = id;
        }

        private ProcessingStep toSnapshot() {
            String label = StepLabels.labelFor(id);
            return new ProcessingStep(
                    id,
                    phase,
                    lifecycle,
                    new StepSummary(before, active, after),
                    startedAt,
                    endedAt,
                    durationMs,
                    detail,
                    ts,
                    lifecycle,
                    label
            );
        }
    }
}
