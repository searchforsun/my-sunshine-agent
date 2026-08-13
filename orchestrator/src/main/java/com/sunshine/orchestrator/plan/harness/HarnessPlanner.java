package com.sunshine.orchestrator.plan.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextAssembler;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Planner-Executor 结构化入口：planNext / selfAssess / synthesizeAnswer。
 * <p>
 * Round 边界：本类只更新 {@code taskQueue} / {@code goalCompletion} / {@code nextDirection}，
 * <b>不</b>追加 {@code RoundRecord}。Worker 执行回写由 {@link WorkerDispatchTool} 独占。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HarnessPlanner {

    public static final String CATALOG_ID = "planner.harness";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HINT_PLAN = "请仅输出 action=plan 的调度单元 JSON（tasks[]）。";
    private static final String HINT_ASSESS = "请仅输出 action=selfAssess 的 JSON（goalCompletion / nextDirection）。";
    private static final String HINT_ANSWER = "请综合回答用户，停止输出 JSON，停止调用工具。";

    private final AgentRuntime agentRuntime;
    private final ContextAssembler contextAssembler;
    private final AgentExecutionProperties executionProperties;

    public void planNext(PlanNotebook notebook, ExecutionStreamContext ctx) {
        int maxAttempts = plannerMaxAttempts();
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String text = invokePlanner(notebook, ctx, HINT_PLAN);
            List<TaskItem> tasks = parsePlanTasks(text);
            if (tasks != null) {
                replaceTaskQueue(notebook, tasks);
                return;
            }
            last = new IllegalStateException("无法解析 plan JSON（attempt=" + attempt + "）: " + preview(text));
            log.warn("[HarnessPlanner] planNext 解析失败 attempt={}/{} preview={}",
                    attempt, maxAttempts, preview(text));
        }
        throw new IllegalStateException("HarnessPlanner planNext 解析失败，已重试 " + maxAttempts, last);
    }

    public void selfAssess(PlanNotebook notebook, ExecutionStreamContext ctx) {
        int maxAttempts = plannerMaxAttempts();
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String text = invokePlanner(notebook, ctx, HINT_ASSESS);
            if (applySelfAssess(notebook, text)) {
                return;
            }
            last = new IllegalStateException("无法解析 selfAssess JSON（attempt=" + attempt + "）: " + preview(text));
            log.warn("[HarnessPlanner] selfAssess 解析失败 attempt={}/{} preview={}",
                    attempt, maxAttempts, preview(text));
        }
        throw new IllegalStateException("HarnessPlanner selfAssess 解析失败，已重试 " + maxAttempts, last);
    }

    public Flux<StreamToken> synthesizeAnswer(PlanNotebook notebook, ExecutionStreamContext ctx) {
        AgentRunRequest request = buildRequest(notebook, ctx, HINT_ANSWER);
        return Flux.defer(() -> {
            WorkerDispatchTool.DispatchSession session = bindDispatchSession(notebook, ctx, request.runId());
            return agentRuntime.run(request)
                    .doFinally(sig -> WorkerDispatchTool.clearSession(session));
        });
    }

    /**
     * 同步调用 Planner ReAct，收集正文。bindSession 包住本次 run，供模型调用 dispatch_worker。
     * RoundRecord 仍只由 WorkerDispatchTool 写入。
     */
    private String invokePlanner(PlanNotebook notebook, ExecutionStreamContext ctx, String phaseHint) {
        AgentRunRequest request = buildRequest(notebook, ctx, phaseHint);
        WorkerDispatchTool.DispatchSession session = bindDispatchSession(notebook, ctx, request.runId());
        try {
            StringBuilder answer = new StringBuilder();
            long timeoutMs = plannerTimeoutMs();
            agentRuntime.run(request)
                    .doOnNext(token -> WorkerDispatchTool.appendAnswerContent(answer, token))
                    .blockLast(Duration.ofMillis(timeoutMs));
            return answer.toString();
        } finally {
            WorkerDispatchTool.clearSession(session);
        }
    }

    private AgentRunRequest buildRequest(PlanNotebook notebook, ExecutionStreamContext ctx, String phaseHint) {
        AssembledContext memory = resolveMemory(ctx);
        int nearKeep = executionProperties.getHarness().getNotebook().getCompression().getNearKeepRounds();
        List<String> injected = List.of(notebook.renderForPlanner(nearKeep));
        String query = buildQuery(ctx, phaseHint);
        return AgentRunRequest.planner(
                memory,
                query,
                injected,
                ctx.userId(),
                ctx.tenantId(),
                ctx.assistantMsgId(),
                ctx.conversationId(),
                0);
    }

    private AssembledContext resolveMemory(ExecutionStreamContext ctx) {
        AssembledContext provided = ctx != null ? ctx.memory() : null;
        if (provided != null && !isBare(provided)) {
            return provided;
        }
        if (ctx != null && StringUtils.hasText(ctx.conversationId()) && StringUtils.hasText(ctx.userId())) {
            try {
                AssembledContext assembled = contextAssembler.assemble(new ContextAssembler.AssembleRequest(
                        ctx.userId(),
                        ctx.tenantId(),
                        ctx.conversationId(),
                        List.of(),
                        ctx.userContent()));
                if (assembled != null) {
                    return assembled;
                }
            } catch (Exception e) {
                log.warn("[HarnessPlanner] ContextAssembler.assemble 失败 conv={}: {}",
                        ctx.conversationId(), e.getMessage());
            }
        }
        return provided != null ? provided : AssembledContext.empty();
    }

    private WorkerDispatchTool.DispatchSession bindDispatchSession(
            PlanNotebook notebook, ExecutionStreamContext ctx, String parentRunId) {
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                notebook,
                List.of(),
                ctx != null ? ctx.userId() : null,
                ctx != null ? ctx.tenantId() : null,
                ctx != null ? ctx.assistantMsgId() : null,
                ctx != null ? ctx.conversationId() : null,
                parentRunId,
                0);
        WorkerDispatchTool.bindSession(session);
        return session;
    }

    static List<TaskItem> parsePlanTasks(String text) {
        JsonNode root = parseObject(text);
        if (root == null) {
            return null;
        }
        String action = textOrEmpty(root, "action");
        if (StringUtils.hasText(action) && !"plan".equals(action)) {
            return null;
        }
        JsonNode tasks = root.get("tasks");
        if (tasks == null || !tasks.isArray()) {
            return null;
        }
        List<TaskItem> items = new ArrayList<>();
        for (JsonNode node : tasks) {
            String taskId = textOrEmpty(node, "taskId");
            String label = textOrEmpty(node, "label");
            if (!StringUtils.hasText(taskId) || !StringUtils.hasText(label)) {
                return null;
            }
            items.add(new TaskItem(
                    taskId.strip(),
                    label.strip(),
                    "pending",
                    readDependsOn(node),
                    textOrEmpty(node, "constraints"),
                    textOrEmpty(node, "expectedOutput"),
                    textOrEmpty(node, "successCriteria")));
        }
        return items;
    }

    static boolean applySelfAssess(PlanNotebook notebook, String text) {
        JsonNode root = parseObject(text);
        if (root == null || notebook == null) {
            return false;
        }
        String action = textOrEmpty(root, "action");
        if (StringUtils.hasText(action) && !"selfAssess".equals(action)) {
            return false;
        }
        if (!root.has("goalCompletion") || !root.get("goalCompletion").isNumber()) {
            return false;
        }
        double completion = root.get("goalCompletion").asDouble();
        notebook.setGoalCompletion(Math.min(1.0, Math.max(0.0, completion)));
        String direction = textOrEmpty(root, "nextDirection");
        notebook.setNextDirection(StringUtils.hasText(direction) ? direction.strip() : null);
        return true;
    }

    static void replaceTaskQueue(PlanNotebook notebook, List<TaskItem> tasks) {
        notebook.getTaskQueue().clear();
        if (tasks != null) {
            notebook.getTaskQueue().addAll(tasks);
        }
    }

    private static JsonNode parseObject(String text) {
        String json = extractJsonObject(text);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return root != null && root.isObject() ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String s = text.strip();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return s.substring(start, end + 1);
    }

    private static List<String> readDependsOn(JsonNode node) {
        JsonNode deps = node.get("dependsOn");
        if (deps == null || !deps.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode dep : deps) {
            if (dep != null && dep.isTextual() && StringUtils.hasText(dep.asText())) {
                out.add(dep.asText().strip());
            }
        }
        return List.copyOf(out);
    }

    private static String textOrEmpty(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        JsonNode value = node.get(field);
        return value.isTextual() || value.isNumber() ? value.asText() : "";
    }

    private static String buildQuery(ExecutionStreamContext ctx, String phaseHint) {
        String user = ctx != null && StringUtils.hasText(ctx.userContent()) ? ctx.userContent().strip() : "";
        if (!StringUtils.hasText(user)) {
            return phaseHint;
        }
        return user + "\n\n" + phaseHint;
    }

    private static boolean isBare(AssembledContext memory) {
        return memory.nearTurns().isEmpty()
                && memory.midTurns().isEmpty()
                && !StringUtils.hasText(memory.l2SystemBlock())
                && !StringUtils.hasText(memory.farSummaryBlock())
                && !StringUtils.hasText(memory.l3MaterialBlock())
                && !StringUtils.hasText(memory.projectGuideBlock());
    }

    private int plannerMaxAttempts() {
        int n = executionProperties.getHarness().getPlanner().getMaxAttempts();
        return n > 0 ? n : 3;
    }

    private long plannerTimeoutMs() {
        long ms = executionProperties.getHarness().getPlanner().getTimeoutMs();
        return ms > 0 ? ms : 300_000L;
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String s = text.strip();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
