package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.common.sandbox.SandboxEditDiffLine;
import com.sunshine.orchestrator.processing.ContentBlock;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.NodeAttemptMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.routing.RoutingTrace;
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
        if (hasText(step.stepSummary())) {
            map.put("stepSummary", step.stepSummary());
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
        if (metadata.tasks() != null && !metadata.tasks().isEmpty()) {
            List<Map<String, Object>> tasks = new ArrayList<>();
            for (TaskBoardItemView item : metadata.tasks()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.id());
                row.put("content", item.content());
                row.put("status", item.status());
                if (item.dependsOn() != null && !item.dependsOn().isEmpty()) {
                    row.put("dependsOn", item.dependsOn());
                }
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
        if (Boolean.TRUE.equals(metadata.cancellable())) {
            map.put("cancellable", true);
        }
        if (metadata.editDiff() != null) {
            map.put("editDiff", editDiffToMap(metadata.editDiff()));
        }
        if (metadata.decision() != null) {
            map.put("decision", decisionToMap(metadata.decision()));
        }
        if (metadata.routingTraces() != null && !metadata.routingTraces().isEmpty()) {
            List<Map<String, Object>> traces = new ArrayList<>();
            for (RoutingTrace trace : metadata.routingTraces()) {
                if (trace == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                if (hasText(trace.layer())) {
                    row.put("layer", trace.layer());
                }
                if (hasText(trace.label())) {
                    row.put("label", trace.label());
                }
                if (hasText(trace.detail())) {
                    row.put("detail", trace.detail());
                }
                if (!row.isEmpty()) {
                    traces.add(row);
                }
            }
            if (!traces.isEmpty()) {
                map.put("routingTraces", traces);
            }
        }
        return map;
    }

    public static StepMetadata metadataFromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        StepMetadata base = null;
        SandboxEditDiff editDiff = editDiffFromMap(map.get("editDiff"));
        if (editDiff != null) {
            base = StepMetadata.withEditDiff(base, editDiff);
        }
        DecisionStepMeta decision = decisionFromMap(map.get("decision"));
        if (decision != null) {
            base = StepMetadata.withDecision(base, decision);
        }
        return base;
    }

    private static Map<String, Object> decisionToMap(DecisionStepMeta decision) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (hasText(decision.token())) {
            map.put("token", decision.token());
        }
        if (hasText(decision.title())) {
            map.put("title", decision.title());
        }
        List<Map<String, Object>> questions = new ArrayList<>();
        if (decision.questions() != null) {
            for (DecisionQuestion question : decision.questions()) {
                if (question == null) {
                    continue;
                }
                Map<String, Object> qRow = new LinkedHashMap<>();
                if (hasText(question.id())) {
                    qRow.put("id", question.id());
                }
                if (hasText(question.prompt())) {
                    qRow.put("prompt", question.prompt());
                }
                List<Map<String, Object>> options = new ArrayList<>();
                if (question.options() != null) {
                    for (DecisionOption option : question.options()) {
                        if (option == null) {
                            continue;
                        }
                        Map<String, Object> optRow = new LinkedHashMap<>();
                        if (hasText(option.id())) {
                            optRow.put("id", option.id());
                        }
                        if (hasText(option.label())) {
                            optRow.put("label", option.label());
                        }
                        options.add(optRow);
                    }
                }
                qRow.put("options", options);
                qRow.put("allowMultiple", question.allowMultiple());
                questions.add(qRow);
            }
        }
        map.put("questions", questions);
        if (decision.expiresAt() != null) {
            map.put("expiresAt", decision.expiresAt());
        }
        if (hasText(decision.outcome())) {
            map.put("outcome", decision.outcome());
        }
        List<Map<String, Object>> answers = new ArrayList<>();
        if (decision.answers() != null) {
            for (DecisionAnswer answer : decision.answers()) {
                if (answer == null) {
                    continue;
                }
                Map<String, Object> aRow = new LinkedHashMap<>();
                if (hasText(answer.questionId())) {
                    aRow.put("questionId", answer.questionId());
                }
                List<String> selected = answer.selectedOptionIds() != null
                        ? List.copyOf(answer.selectedOptionIds())
                        : List.of();
                aRow.put("selectedOptionIds", selected);
                if (hasText(answer.customInput())) {
                    aRow.put("customInput", answer.customInput());
                }
                answers.add(aRow);
            }
        }
        if (!answers.isEmpty()) {
            map.put("answers", answers);
        }
        return map;
    }

    private static DecisionStepMeta decisionFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String token = stringValue(map.get("token"));
        String title = stringValue(map.get("title"));
        List<DecisionQuestion> questions = readDecisionQuestions(map.get("questions"));
        Long expiresAt = map.get("expiresAt") instanceof Number n ? n.longValue() : null;
        String outcome = stringValue(map.get("outcome"));
        List<DecisionAnswer> answers = readDecisionAnswers(map.get("answers"));
        if (token == null && title == null && questions.isEmpty() && answers.isEmpty()
                && outcome == null && expiresAt == null) {
            return null;
        }
        return new DecisionStepMeta(
                token,
                title,
                List.copyOf(questions),
                expiresAt,
                outcome,
                answers.isEmpty() ? null : List.copyOf(answers));
    }

    private static List<DecisionQuestion> readDecisionQuestions(Object raw) {
        List<DecisionQuestion> questions = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) {
            return questions;
        }
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) {
                continue;
            }
            String id = stringValue(row.get("id"));
            String prompt = stringValue(row.get("prompt"));
            List<DecisionOption> options = new ArrayList<>();
            Object optionsObj = row.get("options");
            if (optionsObj instanceof List<?> optionRows) {
                for (Object optObj : optionRows) {
                    if (!(optObj instanceof Map<?, ?> optRow)) {
                        continue;
                    }
                    String optId = stringValue(optRow.get("id"));
                    String label = stringValue(optRow.get("label"));
                    if (optId == null && label == null) {
                        continue;
                    }
                    options.add(new DecisionOption(optId, label));
                }
            }
            boolean allowMultiple = Boolean.TRUE.equals(row.get("allowMultiple"))
                    || "true".equalsIgnoreCase(String.valueOf(row.get("allowMultiple")));
            if (id == null && prompt == null && options.isEmpty()) {
                continue;
            }
            questions.add(new DecisionQuestion(id, prompt, List.copyOf(options), allowMultiple));
        }
        return questions;
    }

    private static List<DecisionAnswer> readDecisionAnswers(Object raw) {
        List<DecisionAnswer> answers = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) {
            return answers;
        }
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) {
                continue;
            }
            String questionId = stringValue(row.get("questionId"));
            List<String> selected = new ArrayList<>();
            Object selectedObj = row.get("selectedOptionIds");
            if (selectedObj instanceof List<?> ids) {
                for (Object idObj : ids) {
                    if (idObj != null) {
                        selected.add(String.valueOf(idObj));
                    }
                }
            }
            String customInput = stringValue(row.get("customInput"));
            if (questionId == null && selected.isEmpty() && customInput == null) {
                continue;
            }
            answers.add(new DecisionAnswer(questionId, List.copyOf(selected), customInput));
        }
        return answers;
    }

    private static Map<String, Object> editDiffToMap(SandboxEditDiff editDiff) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (hasText(editDiff.path())) {
            map.put("path", editDiff.path());
        }
        map.put("contextRadius", editDiff.contextRadius());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (SandboxEditDiffLine line : editDiff.lines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", line.kind());
            row.put("text", line.text() != null ? line.text() : "");
            if (line.oldLine() != null) {
                row.put("oldLine", line.oldLine());
            }
            if (line.newLine() != null) {
                row.put("newLine", line.newLine());
            }
            lines.add(row);
        }
        map.put("lines", lines);
        return map;
    }

    private static SandboxEditDiff editDiffFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String path = stringValue(map.get("path"));
        int contextRadius = 0;
        if (map.get("contextRadius") instanceof Number radius) {
            contextRadius = radius.intValue();
        }
        Object linesObj = map.get("lines");
        if (!(linesObj instanceof List<?> lineRows) || lineRows.isEmpty()) {
            return null;
        }
        List<SandboxEditDiffLine> lines = new ArrayList<>();
        for (Object rowObj : lineRows) {
            if (!(rowObj instanceof Map<?, ?> row)) {
                continue;
            }
            String kind = stringValue(row.get("kind"));
            if (kind == null) {
                continue;
            }
            String text = row.get("text") != null ? String.valueOf(row.get("text")) : "";
            Integer oldLine = row.get("oldLine") instanceof Number n ? n.intValue() : null;
            Integer newLine = row.get("newLine") instanceof Number n ? n.intValue() : null;
            lines.add(new SandboxEditDiffLine(kind, text, oldLine, newLine));
        }
        if (lines.isEmpty()) {
            return null;
        }
        return new SandboxEditDiff(path, contextRadius, List.copyOf(lines));
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
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
