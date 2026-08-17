package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.TokenWrapperMode;
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
import com.sunshine.orchestrator.config.VirtualThreadExecutors;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Planner 元工具 — 按 taskId 调度 Worker（{@code AgentRole.WORKER}）。
 * <p>
 * 注册钩子（Task 8）：在组装 PLANNER toolkit 时调用
 * {@link #registerIntoPlannerToolkit(Toolkit)}；勿注入 MAIN/SUB。
 * 运行前须 {@link #bindSession(DispatchSession)}（由 HarnessPlanner / Loop 设置）。
 * <p>
 * 会话按 assistantMessageId / parentRunId / planner-{runId} 存入 {@link ConcurrentHashMap}，
 * <b>禁止 ThreadLocal</b>（工具在虚拟线程上执行，与 bind 线程不同）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerDispatchTool implements AgentTool {

    public static final String NAME = "dispatch_worker";

    private static final ConcurrentHashMap<String, DispatchSession> BY_MESSAGE_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DispatchSession> BY_RUN_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DispatchSession> BY_BRIDGE_ID = new ConcurrentHashMap<>();

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
            int maxIters,
            String conversationKind,
            ActionSignals signals) {

        /** 引擎侧动作接收标记：plan_submit / self_assess 工具调用后置位，供 planNext/selfAssess 判定。 */
        public record ActionSignals(
                java.util.concurrent.atomic.AtomicBoolean planReceived,
                java.util.concurrent.atomic.AtomicBoolean assessReceived) {
            public static ActionSignals fresh() {
                return new ActionSignals(
                        new java.util.concurrent.atomic.AtomicBoolean(false),
                        new java.util.concurrent.atomic.AtomicBoolean(false));
            }
        }

        String plannerBridgeId() {
            return StringUtils.hasText(parentRunId) ? "planner-" + parentRunId.strip() : null;
        }
    }

    public static void bindSession(DispatchSession session) {
        if (session == null || session.notebook() == null) {
            return;
        }
        if (StringUtils.hasText(session.assistantMessageId())) {
            BY_MESSAGE_ID.put(session.assistantMessageId().strip(), session);
        }
        if (StringUtils.hasText(session.parentRunId())) {
            BY_RUN_ID.put(session.parentRunId().strip(), session);
        }
        String bridgeId = session.plannerBridgeId();
        if (StringUtils.hasText(bridgeId)) {
            BY_BRIDGE_ID.put(bridgeId, session);
        }
    }

    public static void clearSession(DispatchSession session) {
        if (session == null) {
            return;
        }
        if (StringUtils.hasText(session.assistantMessageId())) {
            BY_MESSAGE_ID.remove(session.assistantMessageId().strip(), session);
        }
        if (StringUtils.hasText(session.parentRunId())) {
            BY_RUN_ID.remove(session.parentRunId().strip(), session);
        }
        String bridgeId = session.plannerBridgeId();
        if (StringUtils.hasText(bridgeId)) {
            BY_BRIDGE_ID.remove(bridgeId, session);
        }
    }

    /** 测试清理：移除全部绑定（生产路径用 {@link #clearSession(DispatchSession)}）。 */
    public static void clearAllSessionsForTests() {
        BY_MESSAGE_ID.clear();
        BY_RUN_ID.clear();
        BY_BRIDGE_ID.clear();
    }

    public static DispatchSession currentSession(String lookupKey) {
        return lookupSession(lookupKey);
    }

    static DispatchSession lookupSession(String lookupKey) {
        if (!StringUtils.hasText(lookupKey)) {
            return null;
        }
        String key = lookupKey.strip();
        DispatchSession session = BY_MESSAGE_ID.get(key);
        if (session != null) {
            return session;
        }
        session = BY_RUN_ID.get(key);
        if (session != null) {
            return session;
        }
        session = BY_BRIDGE_ID.get(key);
        if (session != null) {
            return session;
        }
        if (key.startsWith("planner-") && key.length() > "planner-".length()) {
            return BY_RUN_ID.get(key.substring("planner-".length()));
        }
        return null;
    }

    static DispatchSession resolveSessionForToolUse(String toolUseId) {
        if (StringUtils.hasText(toolUseId)) {
            String messageId = StepEventBridge.resolveMessageIdForToolUse(toolUseId);
            DispatchSession byMsg = lookupSession(messageId);
            if (byMsg != null) {
                return byMsg;
            }
            String bridgeId = StepEventBridge.bridgeIdForToolUse(toolUseId);
            DispatchSession byBridge = lookupSession(bridgeId);
            if (byBridge != null) {
                return byBridge;
            }
        }
        return lookupSession(StepEventBridge.activeBridgeId());
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
                    // ConcurrentHashMap + StepEventBridge 均为跨线程；勿依赖 ThreadLocal
                    DispatchSession session = resolveSessionForToolUse(toolUseId);
                    String text = dispatchWorker(taskId, session);
                    return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
                })
                .subscribeOn(VirtualThreadExecutors.scheduler());
    }

    /** 单测 / 同步入口：用 assistantMessageId 或 parentRunId / planner-{runId} 查找会话。 */
    String dispatchWorker(String taskId, String sessionLookupKey) {
        return dispatchWorker(taskId, lookupSession(sessionLookupKey));
    }

    String dispatchWorker(String taskId, DispatchSession session) {
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
                session.parentRunId())
                .withConversationKind(session.conversationKind());
        long timeoutMs = harness.getWorker().getTimeoutMs() > 0
                ? harness.getWorker().getTimeoutMs()
                : 3_600_000L;
        // Planner 直接 dispatch：mainBridge（planner-{parentRunId}）session 存活时 fold Worker 内部
        // 步骤为 worker-{taskId} 一级行 subSteps；Loop 兜底（planner-{loopRunId} 无 session）时不 fold，
        // 骨架由 Loop 自身 emit，避免双写。
        String workerBridgeId = request.resolveBridgeId();
        String mainBridge = session.plannerBridgeId();
        boolean foldActive = StringUtils.hasText(mainBridge) && StepEventBridge.hasSession(mainBridge);
        WorkerTimelineBridge workerTimeline = null;
        if (foldActive) {
            final WorkerTimelineBridge timeline = new WorkerTimelineBridge(task.taskId(), task.label());
            workerTimeline = timeline;
            StepEventBridge.emit(mainBridge, s -> timeline.begin().forEach(s::enqueueAuxiliary));
            // PASS_THROUGH：wrapper 只 fold；原 token 入队供 Flux（正文收集，步骤不进主时间线平铺）
            StepEventBridge.bindTokenWrapper(workerBridgeId, token -> {
                foldStepToken(mainBridge, timeline, token);
                return List.of();
            }, TokenWrapperMode.PASS_THROUGH);
        }
        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            agentRuntime.run(request)
                    .doOnNext(token -> appendAnswerContent(answer, token))
                    .doOnError(failure::set)
                    .blockLast(Duration.ofMillis(timeoutMs));
            if (failure.get() != null) {
                String msg = failTask(nb, task, failure.get());
                emitWorkerTerminal(mainBridge, workerTimeline, msg, false);
                return msg;
            }
            String handoff = answer.toString().strip();
            if (!StringUtils.hasText(handoff)) {
                handoff = "（Worker 未产出正文）";
            }
            completeTask(nb, task, handoff);
            emitWorkerTerminal(mainBridge, workerTimeline, handoff, true);
            return handoff;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isTimeout(cause) || isTimeout(e)) {
                String msg = "Worker 执行超时（" + timeoutMs + "ms）";
                failTask(nb, task, new TimeoutException(msg));
                emitWorkerTerminal(mainBridge, workerTimeline, msg, false);
                return msg;
            }
            String msg = failTask(nb, task, cause != null ? cause : e);
            emitWorkerTerminal(mainBridge, workerTimeline, msg, false);
            return msg;
        } finally {
            if (foldActive) {
                StepEventBridge.unbindTokenWrapper(workerBridgeId);
            }
        }
    }

    private void foldStepToken(String mainBridge, WorkerTimelineBridge bridge, StreamToken token) {
        if (token == null || bridge == null) {
            return;
        }
        if (token.isStep() || token.isStepDelta()) {
            emitWorkerStep(mainBridge, bridge.wrap(token));
        }
    }

    private void emitWorkerStep(String mainBridge, List<StreamToken> tokens) {
        if (!StringUtils.hasText(mainBridge) || tokens == null || tokens.isEmpty()) {
            return;
        }
        StepEventBridge.emit(mainBridge, s -> tokens.forEach(s::enqueueAuxiliary));
    }

    private void emitWorkerTerminal(String mainBridge, WorkerTimelineBridge bridge, String result, boolean ok) {
        if (bridge == null || !StringUtils.hasText(mainBridge)) {
            return;
        }
        emitWorkerStep(mainBridge, bridge.complete(ok ? "完成" : "执行失败", result, ok));
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
