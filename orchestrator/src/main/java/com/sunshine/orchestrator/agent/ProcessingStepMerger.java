package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.processing.NodeAttemptMeta;
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
                step.metadata(),
                step.subSteps()
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
                incoming.metadata() != null ? mergeMetadata(existing.metadata(), incoming.metadata()) : existing.metadata(),
                mergeSubSteps(existing.subSteps(), incoming.subSteps())
        );
    }

    private static java.util.List<ProcessingStep> mergeSubSteps(
            java.util.List<ProcessingStep> existing,
            java.util.List<ProcessingStep> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return existing;
        }
        java.util.List<ProcessingStep> merged = existing != null && !existing.isEmpty()
                ? new java.util.ArrayList<>(existing)
                : new java.util.ArrayList<>();
        for (ProcessingStep step : incoming) {
            upsert(merged, step);
        }
        return merged;
    }

    private static com.sunshine.orchestrator.processing.StepMetadata mergeMetadata(
            com.sunshine.orchestrator.processing.StepMetadata existing,
            com.sunshine.orchestrator.processing.StepMetadata incoming) {
        return com.sunshine.orchestrator.processing.StepMetadata.merge(existing, incoming);
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
            case "done", "error", "skipped", "terminated" -> nonEmptySummary(null, null, s.after());
            default -> nonEmptySummary(null, s.active(), null);
        };
    }

    private static StepSummary nonEmptySummary(String before, String active, String after) {
        if (before == null && active == null && after == null) {
            return null;
        }
        return new StepSummary(before, active, after);
    }

    public static Map<String, Object> stepToMap(ProcessingStep step) {
        return toPersistMap(step);
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
        if (step.subSteps() != null && !step.subSteps().isEmpty()) {
            java.util.List<java.util.Map<String, Object>> nested = new java.util.ArrayList<>();
            for (ProcessingStep sub : step.subSteps()) {
                nested.add(toPersistMap(sub));
            }
            map.put("subSteps", nested);
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
        if (hasText(metadata.rewriteScenarioLabel())) {
            map.put("rewriteScenarioLabel", metadata.rewriteScenarioLabel());
        }
        if (hasText(metadata.skillId())) {
            map.put("skillId", metadata.skillId());
        }
        if (hasText(metadata.plannerMode())) {
            map.put("plannerMode", metadata.plannerMode());
        }
        if (hasText(metadata.routingReason())) {
            map.put("routingReason", metadata.routingReason());
        }
        if (metadata.rewriteInDetail() != null) {
            map.put("rewriteInDetail", metadata.rewriteInDetail());
        }
        if (hasText(metadata.expandSectionTitle())) {
            map.put("expandSectionTitle", metadata.expandSectionTitle());
        }
        if (metadata.hitl() != null) {
            Map<String, Object> hitl = new LinkedHashMap<>();
            if (hasText(metadata.hitl().status())) {
                hitl.put("status", metadata.hitl().status());
            }
            if (hasText(metadata.hitl().token())) {
                hitl.put("token", metadata.hitl().token());
            }
            if (hasText(metadata.hitl().toolDisplayName())) {
                hitl.put("toolDisplayName", metadata.hitl().toolDisplayName());
            }
            if (hasText(metadata.hitl().paramsSummary())) {
                hitl.put("paramsSummary", metadata.hitl().paramsSummary());
            }
            if (metadata.hitl().expiresAt() != null) {
                hitl.put("expiresAt", metadata.hitl().expiresAt());
            }
            if (!hitl.isEmpty()) {
                map.put("hitl", hitl);
            }
        }
        if (metadata.recovery() != null) {
            Map<String, Object> recovery = new LinkedHashMap<>();
            if (hasText(metadata.recovery().status())) {
                recovery.put("status", metadata.recovery().status());
            }
            if (hasText(metadata.recovery().token())) {
                recovery.put("token", metadata.recovery().token());
            }
            if (hasText(metadata.recovery().errorMessage())) {
                recovery.put("errorMessage", metadata.recovery().errorMessage());
            }
            if (metadata.recovery().expiresAt() != null) {
                recovery.put("expiresAt", metadata.recovery().expiresAt());
            }
            if (!recovery.isEmpty()) {
                map.put("recovery", recovery);
            }
        }
        if (metadata.nodeAttempts() != null && !metadata.nodeAttempts().isEmpty()) {
            List<Map<String, Object>> attempts = new ArrayList<>();
            for (NodeAttemptMeta attempt : metadata.nodeAttempts()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("attemptNo", attempt.attemptNo());
                item.put("status", attempt.status());
                if (hasText(attempt.errorClass())) {
                    item.put("errorClass", attempt.errorClass());
                }
                if (hasText(attempt.summary())) {
                    item.put("summary", attempt.summary());
                }
                if (attempt.startedAt() != null) {
                    item.put("startedAt", attempt.startedAt());
                }
                if (attempt.endedAt() != null) {
                    item.put("endedAt", attempt.endedAt());
                }
                attempts.add(item);
            }
            map.put("nodeAttempts", attempts);
        }
        if (metadata.planApproval() != null) {
            Map<String, Object> approval = new LinkedHashMap<>();
            com.sunshine.orchestrator.processing.PlanApprovalMeta pa = metadata.planApproval();
            if (hasText(pa.status())) {
                approval.put("status", pa.status());
            }
            if (hasText(pa.token())) {
                approval.put("token", pa.token());
            }
            if (pa.expiresAt() != null) {
                approval.put("expiresAt", pa.expiresAt());
            }
            if (pa.planGraph() != null && !pa.planGraph().isEmpty()) {
                approval.put("planGraph", pa.planGraph());
            }
            if (pa.rounds() != null && !pa.rounds().isEmpty()) {
                List<Map<String, Object>> rounds = new ArrayList<>();
                for (com.sunshine.orchestrator.processing.PlanApprovalRoundMeta round : pa.rounds()) {
                    if (round == null) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("roundNo", round.roundNo());
                    if (hasText(round.status())) {
                        item.put("status", round.status());
                    }
                    if (hasText(round.userHint())) {
                        item.put("userHint", round.userHint());
                    }
                    if (hasText(round.chainSummary())) {
                        item.put("chainSummary", round.chainSummary());
                    }
                    if (round.createdAt() != null) {
                        item.put("createdAt", round.createdAt());
                    }
                    if (round.resolvedAt() != null) {
                        item.put("resolvedAt", round.resolvedAt());
                    }
                    rounds.add(item);
                }
                approval.put("rounds", rounds);
            }
            if (!approval.isEmpty()) {
                map.put("planApproval", approval);
            }
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

    /** 取消生成时：将 running 的 workflow 节点（含子 Agent subSteps）标为 paused */
    public static void pauseRunningWorkflowNodes(List<ProcessingStep> steps) {
        pauseRunningWorkflowNodes(steps, null);
    }

    public static void pauseRunningWorkflowNodes(List<ProcessingStep> steps, String currentNodeId) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (step.id() == null || !step.id().startsWith("node-")) {
                continue;
            }
            List<ProcessingStep> subSteps = step.subSteps();
            if (subSteps != null && !subSteps.isEmpty()) {
                List<ProcessingStep> updatedSubs = new ArrayList<>(subSteps);
                pauseRunningInPlace(updatedSubs);
                if (!updatedSubs.equals(subSteps)) {
                    step = copyWithSubSteps(step, updatedSubs);
                }
            }
            if (isRunning(step)) {
                step = toPaused(step);
            }
            steps.set(i, step);
        }
        if (org.springframework.util.StringUtils.hasText(currentNodeId)) {
            pauseWorkflowNodeAt(steps, "node-" + currentNodeId.strip());
        }
    }

    private static void pauseWorkflowNodeAt(List<ProcessingStep> steps, String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (!stepId.equals(step.id()) || !isRunning(step)) {
                continue;
            }
            steps.set(i, toPaused(step));
            return;
        }
    }

    private static void pauseRunningInPlace(List<ProcessingStep> steps) {
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (isRunning(step)) {
                steps.set(i, toPaused(step));
            }
        }
    }

    private static ProcessingStep copyWithSubSteps(ProcessingStep step, List<ProcessingStep> subSteps) {
        return new ProcessingStep(
                step.id(),
                step.phase(),
                step.lifecycle(),
                step.summary(),
                step.startedAt(),
                step.endedAt(),
                step.durationMs(),
                step.detail(),
                step.reasoning(),
                step.output(),
                step.result(),
                step.ts(),
                step.status(),
                step.label(),
                step.metadata(),
                subSteps);
    }

    public static String findLastRunningWorkflowNodeId(List<ProcessingStep> steps) {
        if (steps == null) {
            return null;
        }
        for (int i = steps.size() - 1; i >= 0; i--) {
            ProcessingStep step = steps.get(i);
            if (step.id() != null && step.id().startsWith("node-") && isRunning(step)) {
                return step.id().substring("node-".length());
            }
        }
        return null;
    }

    private static boolean isRunning(ProcessingStep step) {
        return "running".equals(step.lifecycle()) || "running".equals(step.status());
    }

    private static ProcessingStep toPaused(ProcessingStep step) {
        StepSummary summary = step.summary();
        StepSummary pausedSummary = new StepSummary(
                summary != null ? summary.before() : null,
                "已暂停",
                "已暂停");
        return new ProcessingStep(
                step.id(),
                step.phase(),
                "paused",
                pausedSummary,
                step.startedAt(),
                System.currentTimeMillis(),
                step.startedAt() != null ? System.currentTimeMillis() - step.startedAt() : step.durationMs(),
                step.detail(),
                step.reasoning(),
                step.output(),
                step.result(),
                System.currentTimeMillis(),
                "paused",
                step.label(),
                step.metadata(),
                step.subSteps());
    }
}
