package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.processing.ContentSegmentCoordinator;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Planner 元工具 — 按 taskId 调度 Worker（{@code AgentRole.WORKER}）。
 * <p>
 * 注册钩子（Task 8）：在组装 PLANNER toolkit 时调用
 * {@link #registerIntoPlannerToolkit(Toolkit)}；勿注入 MAIN/SUB。
 * 运行前须 {@link #bindSession(DispatchSession)}（由 HarnessPlanner / Loop 设置）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerDispatchTool implements AgentTool {

    public static final String NAME = "dispatch_worker";

    private static final ThreadLocal<DispatchSession> SESSION = new ThreadLocal<>();

    private final AgentRuntime agentRuntime;
    private final WorkerContextFactory contextFactory;
    private final AgentExecutionProperties executionProperties;

    /** Planner 运行态：notebook + 审计字段；对齐 SpawnSubagent 的 bridge 上下文模式。 */
    public record DispatchSession(
            PlanNotebook notebook,
            List<String> toolWhitelist,
            String userId,
            String tenantId,
            String assistantMessageId,
            String conversationId,
            String parentRunId,
            int maxIters) {
    }

    public static void bindSession(DispatchSession session) {
        SESSION.set(session);
    }

    public static void clearSession() {
        SESSION.remove();
    }

    public static DispatchSession currentSession() {
        return SESSION.get();
    }

    /**
     * Task 8 注册钩子：仅 PLANNER toolkit 调用。
     * 例：{@code workerDispatchTool.registerIntoPlannerToolkit(toolkit);}
     */
    public void registerIntoPlannerToolkit(Toolkit toolkit) {
        if (toolkit == null) {
            return;
        }
        toolkit.registerAgentTool(this);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "调度 Worker 执行 taskQueue 中指定 taskId 的粗单元；返回 handoff 摘要文本。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("taskId", Map.of("type", "string", "description", "PlanNotebook taskQueue 中的 taskId（必填）"));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("taskId"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
                    String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
                    Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
                    Object raw = input.get("taskId");
                    String taskId = raw != null ? String.valueOf(raw) : null;
                    String text = dispatchWorker(taskId);
                    return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 单测 / 同步入口。 */
    String dispatchWorker(String taskId) {
        DispatchSession session = SESSION.get();
        if (session == null || session.notebook() == null) {
            return errorJson("未绑定 WorkerDispatch 会话（须先 bindSession）");
        }
        if (!StringUtils.hasText(taskId)) {
            return errorJson("taskId 不能为空");
        }
        String id = taskId.strip();
        PlanNotebook nb = session.notebook();
        TaskItem task = findTask(nb, id);
        if (task == null) {
            return errorJson("未找到任务: " + id);
        }
        AgentExecutionProperties.Harness harness = executionProperties.getHarness();
        List<String> whitelist = session.toolWhitelist() != null ? session.toolWhitelist() : List.of();
        replaceTaskStatus(nb, id, "in_progress");
        AssembledContext memory = contextFactory.build(nb, task, harness, whitelist);
        String query = contextFactory.buildDynamicQuery(nb, task);
        int maxIters = session.maxIters() > 0
                ? session.maxIters()
                : executionProperties.getReact().getTaskMaxIters();
        AgentRunRequest request = AgentRunRequest.worker(
                memory,
                query,
                whitelist,
                session.userId(),
                session.tenantId(),
                session.assistantMessageId(),
                session.conversationId(),
                maxIters,
                session.parentRunId());
        long timeoutMs = harness.getWorker().getTimeoutMs() > 0
                ? harness.getWorker().getTimeoutMs()
                : 3_600_000L;
        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            agentRuntime.run(request)
                    .doOnNext(token -> appendAnswerContent(answer, token))
                    .doOnError(failure::set)
                    .blockLast(Duration.ofMillis(timeoutMs));
            if (failure.get() != null) {
                return failTask(nb, task, failure.get());
            }
            String handoff = answer.toString().strip();
            if (!StringUtils.hasText(handoff)) {
                handoff = "（Worker 未产出正文）";
            }
            completeTask(nb, task, handoff);
            return handoff;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isTimeout(cause) || isTimeout(e)) {
                String msg = "Worker 执行超时（" + timeoutMs + "ms）";
                failTask(nb, task, new TimeoutException(msg));
                return msg;
            }
            return failTask(nb, task, cause != null ? cause : e);
        }
    }

    private String failTask(PlanNotebook nb, TaskItem task, Throwable error) {
        String msg = error != null && StringUtils.hasText(error.getMessage())
                ? error.getMessage().strip()
                : "Worker 执行失败";
        log.warn("[WorkerDispatchTool] taskId={} 失败: {}", task.taskId(), msg);
        replaceTaskStatus(nb, task.taskId(), "fail");
        appendRound(nb, withStatus(task, "fail"), "fail", msg);
        return msg;
    }

    private void completeTask(PlanNotebook nb, TaskItem task, String handoff) {
        replaceTaskStatus(nb, task.taskId(), "done");
        appendRound(nb, withStatus(task, "done"), "done", handoff);
        nb.setTotalTasksCompleted(nb.getTotalTasksCompleted() + 1);
    }

    private static void appendRound(PlanNotebook nb, TaskItem task, String status, String summary) {
        // Round 所有权：仅本工具在 Worker 完成/失败时追加。HarnessPlanner / Loop 禁止再 appendRound。
        int roundIndex = nb.getCurrentRound();
        nb.appendRound(new RoundRecord(
                roundIndex,
                task,
                List.of(new NodeResult(task.taskId(), status, summary)),
                nb.getGoalCompletion(),
                status));
        nb.setCurrentRound(roundIndex + 1);
    }

    private static TaskItem withStatus(TaskItem task, String status) {
        return new TaskItem(
                task.taskId(),
                task.label(),
                status,
                task.dependsOn() != null ? task.dependsOn() : List.of(),
                task.constraints(),
                task.expectedOutput(),
                task.successCriteria());
    }

    static void replaceTaskStatus(PlanNotebook nb, String taskId, String status) {
        Deque<TaskItem> queue = nb.getTaskQueue();
        if (queue.isEmpty()) {
            return;
        }
        List<TaskItem> rebuilt = new ArrayList<>(queue.size());
        for (TaskItem item : queue) {
            if (taskId.equals(item.taskId())) {
                rebuilt.add(withStatus(item, status));
            } else {
                rebuilt.add(item);
            }
        }
        queue.clear();
        queue.addAll(new ArrayDeque<>(rebuilt));
    }

    private static TaskItem findTask(PlanNotebook nb, String taskId) {
        for (TaskItem item : nb.getTaskQueue()) {
            if (taskId.equals(item.taskId())) {
                return item;
            }
        }
        return null;
    }

    static void appendAnswerContent(StringBuilder answer, StreamToken token) {
        if (answer == null || token == null || token.text() == null || token.text().isEmpty()) {
            return;
        }
        if (!token.isContent() && !(token.isStepDelta() && "result".equals(token.channel()))) {
            return;
        }
        String incoming = token.text();
        String baseline = answer.toString();
        String delta = ContentSegmentCoordinator.resolveDelta(baseline, incoming);
        if (delta.isEmpty()) {
            return;
        }
        answer.setLength(0);
        answer.append(ContentSegmentCoordinator.advanceBaseline(baseline, incoming, delta));
    }

    private static boolean isTimeout(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof TimeoutException) {
            return true;
        }
        String name = t.getClass().getName();
        return name.contains("Timeout")
                || (t.getMessage() != null && t.getMessage().contains("Did not observe"));
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
