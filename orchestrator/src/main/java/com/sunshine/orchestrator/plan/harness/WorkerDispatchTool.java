package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.AsyncToolRunRegistry;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.SpawnRunRegistry;
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
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Planner 元工具 — 按 taskId 调度 Worker（{@code AgentRole.WORKER}）。
 * <p>
 * v17.7：dispatch 强制异步（background fire-and-forget），立即返回 {runId, taskId}；
 * Planner 经 await_tool_run 收集 handoff。同波多 Worker 并发流式输出。
 * 失败/取消不原地重试：Planner 重派同任务生成新版本 taskId（t1-2/t1-3，上限 3 次），
 * 历史记录保留；failReason 分类 timeout|error|cancelled。
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
    private final PlannerActionTool plannerActionTool;
    private final AsyncToolRunRegistry asyncToolRunRegistry;
    private final SpawnRunRegistry spawnRunRegistry;

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
            String conversationKind) {

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

    /** 运行中 Worker 订阅句柄：用户取消 / 整体取消时 dispose，从源头终止流式输出 */
    private static final ConcurrentHashMap<String, Disposable> RUN_SUBSCRIPTIONS = new ConcurrentHashMap<>();

    @Override
    public String getDescription() {
        return "异步调度 Worker 执行 taskQueue 中指定 taskId 的粗单元：立即返回 runId 不阻塞，"
                + "同消息可并发派发多个 Worker（上限 10，可再调 task_status 查状态），"
                + "后续用 await_tool_run(runIds[]) 批量收集任务摘要；失败/取消后重派同任务生成新版本 taskId。";
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

    /**
     * v17.7 强制异步：立即返回 {ok, runId, taskId}；Worker run 在虚拟线程 fire-and-forget。
     * 同波多 dispatch_worker 并发流式；Planner 后续 await_tool_run 收集。
     */
    String dispatchWorker(String taskId, DispatchSession session) {
        if (session == null || session.notebook() == null) {
            return errorJson("未绑定 WorkerDispatch 会话（须先 bindSession）");
        }
        if (!StringUtils.hasText(taskId)) {
            return errorJson("taskId 不能为空");
        }
        String requestedId = taskId.strip();
        PlanNotebook nb = session.notebook();
        // 版本化重试：同 baseTask 已有失败/取消记录时自动版本 +1；超上限拒绝
        RetryAllocation alloc = allocateRetryVersion(nb, requestedId);
        if (alloc == null) {
            return errorJson("任务 " + TaskItem.stripRetrySuffix(requestedId)
                    + " 已执行 " + TaskItem.MAX_RETRY_INDEX + " 次（重试上限），须改派新任务或收束");
        }
        final String id = alloc.taskId;
        final TaskItem task = alloc.task;
        AgentExecutionProperties.Harness harness = executionProperties.getHarness();
        List<String> whitelist = session.toolWhitelist() != null ? session.toolWhitelist() : List.of();
        if (alloc.parentTaskId != null) {
            replaceTaskStatus(nb, alloc.parentTaskId, keepParentStatus(nb, alloc.parentTaskId));
        }
        // 重派任务进队列（保留父执行历史）；新执行置 in_progress
        upsertTask(nb, task);
        replaceTaskStatus(nb, id, "in_progress");
        // 立即下发 taskBoard 快照：前端 TaskBoard 在 Worker 执行期间显示 → 箭头（in_progress）
        plannerActionTool.emitTaskBoardSnapshot(session, "worker-start");
        AssembledContext memory = contextFactory.build(task);
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
        final String runId = request.runId();
        final long timeoutMs = harness.getWorker().getTimeoutMs() > 0
                ? harness.getWorker().getTimeoutMs()
                : 3_600_000L;
        // Planner 直接 dispatch：mainBridge（planner-{parentRunId}）session 存活时 fold Worker 内部
        // 步骤为 worker-{taskId} 一级行 subSteps；Loop 兜底（planner-{loopRunId} 无 session）时不 fold，
        // 骨架由 Loop 自身 emit，避免双写。
        String workerBridgeId = request.resolveBridgeId();
        final String mainBridge = session.plannerBridgeId();
        boolean foldActive = StringUtils.hasText(mainBridge) && StepEventBridge.hasSession(mainBridge);
        final WorkerTimelineBridge workerTimeline = foldActive ? bindTimeline(mainBridge, workerBridgeId, task, runId) : null;
        // 异步 run 注册：await_tool_run 可收集；墙钟超时/用户取消经 onCancel 委托
        if (!asyncToolRunRegistry.tryAcquireSlot(session.assistantMessageId())) {
            if (foldActive) {
                StepEventBridge.unbindTokenWrapper(workerBridgeId);
            }
            replaceTaskStatus(nb, id, "pending");
            return errorJson("本消息 Worker 并发已达上限，请先 await_tool_run 收集已派发任务");
        }
        asyncToolRunRegistry.registerWithId(
                runId,
                AsyncToolRunRegistry.Kind.WORKER_DISPATCH,
                session.assistantMessageId(),
                session.conversationId(),
                timeoutMs,
                () -> spawnRunRegistry.cancel(runId));
        // 用户单独取消 worker 卡：复用 SpawnRunRegistry（AgentScope 2.0 interrupt 为协作式空操作，
        // 不能依赖 agent.interrupt 触发终态；取消时经 onUserCancel 直写 paused + TaskBoard + async 终态）
        spawnRunRegistry.register(runId, session.assistantMessageId(), task.label(), mainBridge, null);
        spawnRunRegistry.bindOnUserCancel(runId, () -> {
            // 用户取消：先 dispose 订阅终止流式，再写终态（防取消后仍持续折叠输出）
            stopRunSubscription(runId);
            if (workerTimeline != null) {
                emitWorkerStep(mainBridge, workerTimeline.cancel("用户取消"));
            }
            cancelTask(nb, id, "cancelled");
            plannerActionTool.emitTaskBoardSnapshot(session, "worker-cancelled");
            asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.CANCELLED, "用户取消");
        });
        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Disposable subscription = agentRuntime.run(request)
                .doOnNext(token -> {
                    appendAnswerContent(answer, token);
                    asyncToolRunRegistry.updatePartial(runId, answer.toString());
                })
                .doOnError(failure::set)
                .subscribeOn(VirtualThreadExecutors.scheduler())
                .subscribe(
                        token -> { },
                        err -> finishWorkerRun(runId, id, session, mainBridge, workerTimeline,
                                answer, failure, timeoutMs, err),
                        () -> finishWorkerRun(runId, id, session, mainBridge, workerTimeline,
                                answer, failure, timeoutMs, null));
        RUN_SUBSCRIPTIONS.put(runId, subscription);
        return "{\"ok\":true,\"runId\":\"" + escape(runId) + "\",\"taskId\":\"" + escape(id)
                + "\",\"status\":\"running\"}";
    }

    /** dispose 订阅：终止 Worker 流式输出（取消后不再向主时间线折叠新内容）。 */
    private static void stopRunSubscription(String runId) {
        Disposable subscription = RUN_SUBSCRIPTIONS.remove(runId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    /**
     * 版本化重试分配：requestedId 直查 taskQueue；未命中且同 baseTask 存在失败/取消历史时
     * 生成下一版本（t1 → t1-2 → t1-3）；retryIndex 超 {@link TaskItem#MAX_RETRY_INDEX} 返回 null（拒绝派发）。
     */
    static final class RetryAllocation {
        final String taskId;
        final TaskItem task;
        final String parentTaskId;

        RetryAllocation(String taskId, TaskItem task, String parentTaskId) {
            this.taskId = taskId;
            this.task = task;
            this.parentTaskId = parentTaskId;
        }
    }

    static RetryAllocation allocateRetryVersion(PlanNotebook nb, String requestedId) {
        TaskItem direct = findTask(nb, requestedId);
        if (direct != null) {
            // 直接命中：pending/done 直接执行；fail/cancelled 生成下一版本（不原地重试）
            String status = direct.status();
            if ("pending".equals(status) || "in_progress".equals(status)) {
                return new RetryAllocation(direct.taskId(), ensureVersioned(direct), null);
            }
            if ("done".equals(status) || "obsolete".equals(status)) {
                return new RetryAllocation(direct.taskId(), ensureVersioned(direct), null);
            }
            return nextRetryOf(nb, direct);
        }
        // 未直接命中：可能 Planner 重派时传的是 base id（t1），找同 base 最新失败/取消执行
        String base = TaskItem.stripRetrySuffix(requestedId);
        TaskItem latestTerminal = null;
        for (TaskItem item : nb.snapshotQueue()) {
            String itemBase = StringUtils.hasText(item.baseTaskId())
                    ? item.baseTaskId()
                    : TaskItem.stripRetrySuffix(item.taskId());
            if (base.equals(itemBase) && isTerminalFailed(item.status())) {
                if (latestTerminal == null || retryIndexOf(item) >= retryIndexOf(latestTerminal)) {
                    latestTerminal = item;
                }
            }
        }
        if (latestTerminal == null) {
            return null;
        }
        return nextRetryOf(nb, latestTerminal);
    }

    private static RetryAllocation nextRetryOf(PlanNotebook nb, TaskItem failed) {
        int nextIndex = retryIndexOf(failed) + 1;
        if (nextIndex > TaskItem.MAX_RETRY_INDEX) {
            return null;
        }
        String nextId = TaskItem.nextRetryTaskId(failed.taskId());
        if (findTask(nb, nextId) != null) {
            return null;
        }
        TaskItem retry = new TaskItem(
                nextId,
                failed.label(),
                "pending",
                failed.dependsOn() != null ? failed.dependsOn() : List.of(),
                failed.constraints(),
                failed.expectedOutput(),
                failed.successCriteria(),
                baseOf(failed),
                nextIndex,
                failed.taskId(),
                null);
        return new RetryAllocation(nextId, retry, failed.taskId());
    }

    private static TaskItem ensureVersioned(TaskItem task) {
        if (task.retryIndex() > 0 && StringUtils.hasText(task.baseTaskId())) {
            return task;
        }
        return new TaskItem(
                task.taskId(), task.label(), task.status(),
                task.dependsOn() != null ? task.dependsOn() : List.of(),
                task.constraints(), task.expectedOutput(), task.successCriteria(),
                TaskItem.stripRetrySuffix(task.taskId()), 1, null, null);
    }

    private static String baseOf(TaskItem task) {
        return StringUtils.hasText(task.baseTaskId())
                ? task.baseTaskId()
                : TaskItem.stripRetrySuffix(task.taskId());
    }

    private static int retryIndexOf(TaskItem task) {
        if (task.retryIndex() > 0) {
            return task.retryIndex();
        }
        String id = task.taskId();
        int dash = id.lastIndexOf('-');
        if (dash <= 0) {
            return 1;
        }
        try {
            int parsed = Integer.parseInt(id.substring(dash + 1));
            return parsed > 0 ? parsed : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static boolean isTerminalFailed(String status) {
        return "fail".equals(status) || "cancelled".equals(status);
    }

    /** 保留父执行终态（fail/cancelled 原样，不清除历史） */
    private static String keepParentStatus(PlanNotebook nb, String parentTaskId) {
        TaskItem parent = findTask(nb, parentTaskId);
        return parent != null ? parent.status() : "fail";
    }

    /** 新任务进队列（同 taskId 覆盖；否则 append 到队尾） */
    static void upsertTask(PlanNotebook nb, TaskItem task) {
        nb.upsertTask(task);
    }

    /** Worker 时间线绑定：begin 骨架 + PASS_THROUGH wrapper 折叠内部步骤/正文到父卡。 */
    private WorkerTimelineBridge bindTimeline(String mainBridge, String workerBridgeId, TaskItem task, String runId) {
        // 任务契约 = 稳定前缀 prompt（taskGoal/constraints/expectedOutput/successCriteria 填充 harness.worker 模板），
        // 经 metadata.spawnPrompt 下发，前端 worker 抽屉「传入提示词」展示。
        String taskContract = contextFactory.buildStablePrefix(task);
        // parentStepId 用版本化 id（t1-1/t1-2…）：首次执行 worker-t1-1、重派 worker-t1-2，天然不覆盖历史卡
        final WorkerTimelineBridge timeline = new WorkerTimelineBridge(task.versionedId(), task.label(), taskContract, runId);
        // begin 骨架走主桥 emit（与原 PASS_THROUGH 路径行为一致）
        StepEventBridge.emit(mainBridge, s -> timeline.begin().forEach(s::enqueueAuxiliary));
        // PASS_THROUGH：原 worker 内部 step / step_delta token 入 hookQueue 供 Flux 消费，
        // 同时 wrapper 在每次 fold 时通过 mainBridge emit 把父步 token 直刷 GenerationJob，
        // 前端可实时看到 worker 内部 subSteps 累积（与 spawn_subagent 流式输出同构）。
        StepEventBridge.bindTokenWrapper(workerBridgeId, token -> {
            // 取消/终态后不再折叠：用户取消卡后停止继续流式输出（p9），
            // 避免「状态已 cancelled 但正文仍在流」的观感不一致。
            if (!spawnRunRegistry.isCancelled(runId) && !isAsyncAlreadyTerminal(runId)) {
                foldStepToken(mainBridge, timeline, token);
            }
            return List.of();
        }, TokenWrapperMode.PASS_THROUGH);
        return timeline;
    }

    private void foldStepToken(String mainBridge, WorkerTimelineBridge bridge, StreamToken token) {
        if (token == null || bridge == null) {
            return;
        }
        // step / step_delta -> subSteps；content -> 父步 result 流式（见 Bridge.wrap）
        if (token.isStep() || token.isStepDelta()
                || token.isContent() || token.isContentStart() || token.isContentEnd()) {
            emitWorkerStep(mainBridge, bridge.wrap(token));
        }
    }

    /** 折叠后的父步 token 直刷 GenerationJob，前端实时看到 worker 内部 subSteps 累积。 */
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
        emitWorkerStep(mainBridge, bridge.complete(result, ok));
    }

    /**
     * 异步 run 终态回调：完成/失败/超时/取消统一收束。
     * claim 语义与 SpawnSubagentTool.finishBackgroundSpawn 同构——仅胜者写时间线。
     */
    private void finishWorkerRun(
            String runId,
            String taskId,
            DispatchSession session,
            String mainBridge,
            WorkerTimelineBridge workerTimeline,
            StringBuilder answer,
            AtomicReference<Throwable> failure,
            long timeoutMs,
            Throwable subscribeError) {
        try {
            PlanNotebook nb = session.notebook();
            // 墙钟/用户取消已终态：禁止再写 success/fail 时间线
            if (isAsyncAlreadyTerminal(runId)) {
                return;
            }
            Throwable err = subscribeError != null ? subscribeError : failure.get();
            if (spawnRunRegistry.isCancelled(runId) || isInterrupted(err)) {
                String msg = "用户取消";
                cancelTask(nb, taskId, "cancelled");
                // 与 subagent 取消同构：paused + summary.after=已取消（勿走 complete(false) 落 error）
                if (workerTimeline != null) {
                    emitWorkerStep(mainBridge, workerTimeline.cancel(msg));
                }
                asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.CANCELLED, msg);
                plannerActionTool.emitTaskBoardSnapshot(session, "worker-cancelled");
                return;
            }
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                if (isTimeout(cause) || isTimeout(err)) {
                    String msg = "Worker 执行超时（" + timeoutMs + "ms）";
                    failTaskWithReason(nb, taskId, "timeout", msg);
                    if (workerTimeline != null) {
                        emitWorkerTerminal(mainBridge, workerTimeline, msg, false);
                    }
                    asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.WALL_TIMEOUT, msg);
                    plannerActionTool.emitTaskBoardSnapshot(session, "worker-fail");
                    return;
                }
                String msg = err != null && StringUtils.hasText(err.getMessage())
                        ? err.getMessage().strip()
                        : "Worker 执行失败";
                failTaskWithReason(nb, taskId, "error", msg);
                if (workerTimeline != null) {
                    emitWorkerTerminal(mainBridge, workerTimeline, msg, false);
                }
                asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.ERROR, msg);
                plannerActionTool.emitTaskBoardSnapshot(session, "worker-fail");
                return;
            }
            if (spawnRunRegistry.isCancelled(runId) || isAsyncAlreadyTerminal(runId)) {
                return;
            }
            String handoff = answer.toString().strip();
            if (!StringUtils.hasText(handoff)) {
                handoff = "（Worker 未产出正文）";
            }
            completeTask(nb, taskId, handoff);
            if (workerTimeline != null) {
                emitWorkerTerminal(mainBridge, workerTimeline, handoff, true);
            }
            // 先 claim DONE；仅胜者写 TaskBoard 终态快照
            asyncToolRunRegistry.complete(runId, AsyncToolRunRegistry.Status.DONE, handoff);
            plannerActionTool.emitTaskBoardSnapshot(session, "worker-done");
        } finally {
            stopRunSubscription(runId);
            StepEventBridge.unbindTokenWrapper("worker-" + runId);
            spawnRunRegistry.unregister(runId);
        }
    }

    private boolean isAsyncAlreadyTerminal(String runId) {
        AsyncToolRunRegistry.Snapshot snap = asyncToolRunRegistry.peek(runId);
        return snap != null && snap.status() != AsyncToolRunRegistry.Status.RUNNING;
    }

    private void failTaskWithReason(PlanNotebook nb, String taskId, String failReason, String message) {
        TaskItem task = findTask(nb, taskId);
        if (task == null) {
            return;
        }
        log.warn("[WorkerDispatchTool] taskId={} 失败({}): {}", taskId, failReason, message);
        TaskItem failed = task.withStatus("fail", failReason);
        replaceTask(nb, failed);
        appendRound(nb, failed, "fail", message);
    }

    private void cancelTask(PlanNotebook nb, String taskId, String reason) {
        TaskItem task = findTask(nb, taskId);
        if (task == null) {
            return;
        }
        TaskItem cancelled = task.withStatus("cancelled", reason);
        replaceTask(nb, cancelled);
        appendRound(nb, cancelled, "cancelled", reason);
    }

    private void completeTask(PlanNotebook nb, String taskId, String handoff) {
        TaskItem task = findTask(nb, taskId);
        if (task == null) {
            return;
        }
        TaskItem done = task.withStatus("done", null);
        replaceTask(nb, done);
        appendRound(nb, done, "done", handoff);
        nb.setTotalTasksCompleted(nb.getTotalTasksCompleted() + 1);
    }

    private static void appendRound(PlanNotebook nb, TaskItem task, String status, String summary) {
        // Round 所有权：仅本工具在 Worker 完成/失败/取消时追加。HarnessPlanner / Loop 禁止再 appendRound。
        int roundIndex = nb.getCurrentRound();
        nb.appendRound(new RoundRecord(
                roundIndex,
                task,
                List.of(new NodeResult(task.taskId(), status, summary)),
                nb.getGoalCompletion(),
                status));
        nb.setCurrentRound(roundIndex + 1);
    }

    private static void replaceTask(PlanNotebook nb, TaskItem updated) {
        nb.replaceTask(updated);
    }

    static void replaceTaskStatus(PlanNotebook nb, String taskId, String status) {
        nb.replaceTaskStatus(taskId, status);
    }

    private static TaskItem findTask(PlanNotebook nb, String taskId) {
        return nb.findTask(taskId);
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

    private static boolean isInterrupted(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof InterruptedException) {
                return true;
            }
            String name = cur.getClass().getSimpleName();
            if (name.contains("Interrupt")) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase().contains("interrupt")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
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
