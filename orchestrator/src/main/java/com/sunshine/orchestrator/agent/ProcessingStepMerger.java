package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
