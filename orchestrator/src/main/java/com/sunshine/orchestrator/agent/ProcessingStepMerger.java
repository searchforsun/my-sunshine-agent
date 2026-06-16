package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.processing.StepLabels;
import com.sunshine.orchestrator.processing.StepSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理步骤合并与 JSON 序列化
 */
public final class ProcessingStepMerger {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<List<ProcessingStep>> STEP_LIST = new TypeReference<>() {};

    private ProcessingStepMerger() {
    }

    public static void upsert(List<ProcessingStep> steps, ProcessingStep incoming) {
        if (incoming == null) {
            return;
        }
        Map<String, ProcessingStep> byId = new LinkedHashMap<>();
        for (ProcessingStep step : steps) {
            byId.put(step.id(), step);
        }
        ProcessingStep existing = byId.get(incoming.id());
        byId.put(incoming.id(), existing == null ? incoming : mergeSteps(existing, incoming));
        steps.clear();
        steps.addAll(byId.values());
    }

    public static void applyDelta(List<ProcessingStep> steps, String stepId, String channel, String text) {
        if (stepId == null || channel == null || text == null || text.isEmpty()) {
            return;
        }
        Map<String, ProcessingStep> byId = new LinkedHashMap<>();
        for (ProcessingStep step : steps) {
            byId.put(step.id(), step);
        }
        ProcessingStep existing = byId.get(stepId);
        if (existing == null) {
            existing = ProcessingStep.running(stepId, stepId, StepLabels.labelFor(stepId));
        }
        byId.put(stepId, applyDeltaToStep(existing, channel, text));
        steps.clear();
        steps.addAll(byId.values());
    }

    private static ProcessingStep applyDeltaToStep(ProcessingStep step, String channel, String text) {
        return switch (channel) {
            case "reasoning" -> copyStep(step,
                    concat(step.reasoning(), text),
                    step.output(),
                    step.result());
            case "output" -> copyStep(step,
                    step.reasoning(),
                    concat(step.output(), text),
                    step.result());
            case "result" -> copyStep(step, step.reasoning(), step.output(), text);
            default -> copyStep(step, step.reasoning(), concat(step.output(), text), step.result());
        };
    }

    private static String concat(String existing, String chunk) {
        if (existing == null || existing.isEmpty()) {
            return chunk;
        }
        if (chunk == null || chunk.isEmpty()) {
            return existing;
        }
        return existing + chunk;
    }

    private static ProcessingStep copyStep(
            ProcessingStep step, String reasoning, String output, String result) {
        return new ProcessingStep(
                step.id(),
                step.phase(),
                step.lifecycle() != null ? step.lifecycle() : "running",
                step.summary(),
                step.startedAt(),
                step.endedAt(),
                step.durationMs(),
                step.detail(),
                reasoning,
                output,
                result,
                step.ts(),
                step.status() != null ? step.status() : "running",
                step.label()
        );
    }

    private static String longer(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return b.length() >= a.length() && b.startsWith(a) ? b : a + b;
    }

    private static ProcessingStep mergeSteps(ProcessingStep existing, ProcessingStep incoming) {
        Long startedAt = minNonNull(existing.startedAt(), incoming.startedAt());
        StepSummary summary = mergeSummary(existing.summary(), incoming.summary());
        Long endedAt = moreComplete(existing.endedAt(), incoming.endedAt());
        Long durationMs = computeDuration(startedAt, endedAt,
                existing.durationMs(), incoming.durationMs());

        return new ProcessingStep(
                incoming.id(),
                incoming.phase() != null ? incoming.phase() : existing.phase(),
                incoming.lifecycle() != null ? incoming.lifecycle() : existing.lifecycle(),
                summary,
                startedAt,
                endedAt,
                durationMs,
                incoming.detail() != null ? incoming.detail() : existing.detail(),
                longer(existing.reasoning(), incoming.reasoning()),
                longer(existing.output(), incoming.output()),
                incoming.result() != null ? incoming.result() : existing.result(),
                Math.max(existing.ts(), incoming.ts()),
                incoming.status() != null ? incoming.status() : existing.status(),
                incoming.label() != null ? incoming.label() : existing.label()
        );
    }

    private static StepSummary mergeSummary(StepSummary existing, StepSummary incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }
        return new StepSummary(
                incoming.before() != null ? incoming.before() : existing.before(),
                incoming.active() != null ? incoming.active() : existing.active(),
                incoming.after() != null ? incoming.after() : existing.after()
        );
    }

    private static Long minNonNull(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static Long moreComplete(Long existing, Long incoming) {
        return incoming != null ? incoming : existing;
    }

    private static Long computeDuration(Long startedAt, Long endedAt, Long existing, Long incoming) {
        if (startedAt != null && endedAt != null) {
            return endedAt - startedAt;
        }
        return incoming != null ? incoming : existing;
    }

    public static String toJson(List<ProcessingStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        try {
            return OM.writeValueAsString(steps);
        } catch (Exception e) {
            return null;
        }
    }

    public static List<ProcessingStep> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new ArrayList<>(OM.readValue(json, STEP_LIST));
        } catch (Exception e) {
            return List.of();
        }
    }
}
