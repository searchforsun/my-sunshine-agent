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
                if (event.summary() != null) {
                    state.active = event.summary();
                }
                if (event.metadata() != null) {
                    state.metadata = event.metadata();
                }
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
            case PAUSE -> {
                state.lifecycle = "paused";
                state.active = event.summary();
                state.after = event.summary();
                state.endedAt = event.ts();
                if (state.startedAt == null) {
                    state.startedAt = event.ts();
                }
                if (state.startedAt != null) {
                    state.durationMs = event.ts() - state.startedAt;
                }
                if (event.detail() != null) {
                    state.detail = event.detail();
                }
            }
            case TERMINATE -> {
                state.lifecycle = "terminated";
                state.after = event.summary();
                finish(state, event);
            }
        }
    }

    /**
     * 流式 append 到步骤文本字段（reasoning / output）或覆盖 result
     */
    public void appendDelta(String stepId, String channel, String text, long ts) {
        if (stepId == null || channel == null || text == null || text.isEmpty()) {
            return;
        }
        StepState state = steps.computeIfAbsent(stepId, StepState::new);
        state.ts = ts;
        if (state.lifecycle == null) {
            state.lifecycle = "running";
        }
        if (state.startedAt == null) {
            state.startedAt = ts;
        }
        switch (channel) {
            case "reasoning" -> state.reasoning = concat(state.reasoning, text);
            case "output" -> state.output = concat(state.output, text);
            case "result" -> state.result = text;
            default -> state.output = concat(state.output, text);
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

    /** Workflow 节点步骤 label 覆盖（与 SSE step.label 对齐） */
    public void bindLabel(String stepId, String label) {
        if (stepId == null || label == null || label.isBlank()) {
            return;
        }
        steps.computeIfAbsent(stepId, StepState::new).labelOverride = label.strip();
    }

    private static void finish(StepState state, ProcessingEvent event) {
        state.endedAt = event.ts();
        if (state.startedAt != null) {
            state.durationMs = state.endedAt - state.startedAt;
        }
        state.after = event.summary();
        if (event.detail() != null) {
            state.detail = event.detail();
            if (state.result == null) {
                state.result = event.detail();
            }
        }
        if (event.metadata() != null) {
            state.metadata = event.metadata();
        } else if (state.metadata != null && state.metadata.recovery() != null
                && !NodeRecoveryMeta.STATUS_SKIPPED.equals(state.metadata.recovery().status())) {
            // 重试成功后 complete 不带 recovery，须清除 retry 态
            state.metadata = StepMetadata.withoutRecovery(state.metadata);
        }
    }

    private static String concat(String existing, String chunk) {
        return existing == null ? chunk : existing + chunk;
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
        private String reasoning;
        private String output;
        private String result;
        private StepMetadata metadata;
        private String labelOverride;
        private long ts;

        private StepState(String id) {
            this.id = id;
        }

        private ProcessingStep toSnapshot() {
            String label = labelOverride != null && !labelOverride.isBlank()
                    ? labelOverride
                    : StepLabels.labelFor(id);
            return new ProcessingStep(
                    id,
                    phase,
                    lifecycle,
                    new StepSummary(before, active, after),
                    startedAt,
                    endedAt,
                    durationMs,
                    detail,
                    reasoning,
                    output,
                    result,
                    ts,
                    lifecycle,
                    label,
                    metadata,
                    null
            );
        }
    }
}
