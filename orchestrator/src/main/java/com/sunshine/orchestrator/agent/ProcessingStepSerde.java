package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.processing.ContentBlock;
import com.sunshine.orchestrator.processing.NodeAttemptMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 处理步骤 JSON / Map 序列化（SSE 与落库） */
public final class ProcessingStepSerde {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<List<ProcessingStep>> STEP_LIST = new TypeReference<>() {};

    private ProcessingStepSerde() {
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
            // paused：用户取消/中断终态，与 done 一样下发 after（如「已取消」）
            case "done", "error", "skipped", "terminated", "paused" ->
                    nonEmptySummary(null, null, s.after());
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
        if (step.label() != null) {
            map.put("label", step.label());
        }
        if (step.metadata() != null && !step.metadata().isEmpty()) {
            map.put("metadata", metadataToMap(step.metadata()));
        }
        if (step.contentBlocks() != null && !step.contentBlocks().isEmpty()) {
            java.util.List<java.util.Map<String, Object>> blocks = new java.util.ArrayList<>();
            for (ContentBlock block : step.contentBlocks()) {
                java.util.Map<String, Object> row = new LinkedHashMap<>();
                row.put("segmentId", block.segmentId());
                if (hasText(block.afterStepId())) {
                    row.put("afterStepId", block.afterStepId());
                }
                row.put("text", block.text());
                blocks.add(row);
            }
            map.put("contentBlocks", blocks);
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
        if (metadata.tasks() != null && !metadata.tasks().isEmpty()) {
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (TaskBoardItemView item : metadata.tasks()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.id());
                row.put("content", item.content());
                row.put("status", item.status());
                tasks.add(row);
            }
            map.put("tasks", tasks);
        }
        if (metadata.taskRevision() != null) {
            map.put("taskRevision", metadata.taskRevision());
        }
        if (hasText(metadata.taskProgress())) {
            map.put("taskProgress", metadata.taskProgress());
        }
        if (hasText(metadata.sandboxPath())) {
            map.put("sandboxPath", metadata.sandboxPath());
        }
        if (hasText(metadata.sandboxSearchRoot())) {
            map.put("sandboxSearchRoot", metadata.sandboxSearchRoot());
        }
        if (hasText(metadata.spawnPrompt())) {
            map.put("spawnPrompt", metadata.spawnPrompt());
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
