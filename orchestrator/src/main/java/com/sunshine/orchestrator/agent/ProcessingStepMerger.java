package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.processing.StepLabels;
import com.sunshine.orchestrator.processing.StepMetadata;
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
                    appendReasoning(step.reasoning(), text),
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

    /** ReAct reasoning 已由 Hook 原生 incrementalChunk 保证为真增量 */
    static String appendReasoning(String existing, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return existing;
        }
        if (existing == null || existing.isEmpty()) {
            return chunk;
        }
        return existing + chunk;
    }

    /** done 态 step 的 reasoning 为全量；running 态 step_delta 仍为增量拼接 */
    private static String mergeReasoning(ProcessingStep existing, ProcessingStep incoming) {
        if (incoming.reasoning() == null) {
            return existing.reasoning();
        }
        if (isDone(incoming)) {
            return incoming.reasoning();
        }
        return appendReasoning(existing.reasoning(), incoming.reasoning());
    }

    private static boolean isDone(ProcessingStep step) {
        return "done".equals(step.lifecycle()) || "done".equals(step.status());
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
                step.label(),
                step.metadata()
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
                mergeReasoning(existing, incoming),
                longer(existing.output(), incoming.output()),
                incoming.result() != null ? incoming.result() : existing.result(),
                Math.max(existing.ts(), incoming.ts()),
                incoming.status() != null ? incoming.status() : existing.status(),
                incoming.label() != null ? incoming.label() : existing.label(),
                incoming.metadata() != null ? incoming.metadata() : existing.metadata()
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

    /** 落库用：summary 仅保留当前阶段一行，省略空的可展开字段 */
    public static String toPersistJson(List<ProcessingStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>(steps.size());
            for (ProcessingStep step : steps) {
                rows.add(toPersistMap(step));
            }
            return OM.writeValueAsString(rows);
        } catch (Exception e) {
            return null;
        }
    }

    /** SSE / 落库：只暴露当前 lifecycle 对应的一行 summary */
    public static StepSummary currentPhaseSummary(ProcessingStep step) {
        if (step == null || step.summary() == null) {
            return null;
        }
        StepSummary s = step.summary();
        String lifecycle = step.lifecycle() != null ? step.lifecycle() : "running";
        return switch (lifecycle) {
            case "pending" -> nonEmptySummary(s.before(), null, null);
            case "running" -> nonEmptySummary(null, s.active(), null);
            case "done", "error", "skipped" -> nonEmptySummary(null, null, s.after());
            default -> nonEmptySummary(null, s.active(), null);
        };
    }

    private static StepSummary nonEmptySummary(String before, String active, String after) {
        if (before == null && active == null && after == null) {
            return null;
        }
        return new StepSummary(before, active, after);
    }

    private static Map<String, Object> toPersistMap(ProcessingStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.id());
        if (step.phase() != null) {
            map.put("phase", step.phase());
        }
        if (step.lifecycle() != null) {
            map.put("lifecycle", step.lifecycle());
        }
        StepSummary summary = currentPhaseSummary(step);
        if (summary != null) {
            Map<String, Object> summaryMap = summaryToMap(summary);
            if (!summaryMap.isEmpty()) {
                map.put("summary", summaryMap);
            }
        }
        if (step.startedAt() != null) {
            map.put("startedAt", step.startedAt());
        }
        if (step.endedAt() != null) {
            map.put("endedAt", step.endedAt());
        }
        if (step.durationMs() != null) {
            map.put("durationMs", step.durationMs());
        }
        if (hasText(step.detail())) {
            map.put("detail", step.detail());
        }
        if (hasText(step.reasoning())) {
            map.put("reasoning", step.reasoning());
        }
        if (hasText(step.output())) {
            map.put("output", step.output());
        }
        if (hasText(step.result())) {
            map.put("result", step.result());
        }
        map.put("ts", step.ts());
        if (step.status() != null) {
            map.put("status", step.status());
        }
        if (step.label() != null) {
            map.put("label", step.label());
        }
        if (step.metadata() != null && !step.metadata().isEmpty()) {
            map.put("metadata", metadataToMap(step.metadata()));
        }
        return map;
    }

    public static Map<String, Object> metadataToMap(StepMetadata metadata) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (metadata.hitCount() != null) {
            map.put("hitCount", metadata.hitCount());
        }
        if (metadata.sources() != null && !metadata.sources().isEmpty()) {
            map.put("sources", metadata.sources());
        }
        if (metadata.rewriteApplied() != null) {
            map.put("rewriteApplied", metadata.rewriteApplied());
        }
        if (metadata.rewriteLatencyMs() != null) {
            map.put("rewriteLatencyMs", metadata.rewriteLatencyMs());
        }
        if (hasText(metadata.rewriteFrom())) {
            map.put("rewriteFrom", metadata.rewriteFrom());
        }
        if (hasText(metadata.rewriteTo())) {
            map.put("rewriteTo", metadata.rewriteTo());
        }
        if (hasText(metadata.rewriteScenario())) {
            map.put("rewriteScenario", metadata.rewriteScenario());
        }
        return map;
    }

    public static Map<String, Object> summaryToMap(StepSummary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (hasText(summary.before())) {
            map.put("before", summary.before());
        }
        if (hasText(summary.active())) {
            map.put("active", summary.active());
        }
        if (hasText(summary.after())) {
            map.put("after", summary.after());
        }
        return map;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
