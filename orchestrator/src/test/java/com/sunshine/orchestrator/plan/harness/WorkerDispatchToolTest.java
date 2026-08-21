package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.AsyncToolRunRegistry;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.SpawnRunRegistry;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.StepEventBridgeRegistry;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerDispatchToolTest {

    @Mock
    private AgentRuntime agentRuntime;

    @Mock
    private PlannerActionTool plannerActionTool;

    private PromptCatalogHolder catalogHolder;
    private WorkerContextFactory contextFactory;
    private AgentExecutionProperties executionProperties;
    private WorkerDispatchTool tool;
    private StepEventBridgeRegistry registry;
    private AsyncToolRunRegistry asyncToolRunRegistry;
    private SpawnRunRegistry spawnRunRegistry;

    @BeforeEach
    void setUp() {
        catalogHolder = new PromptCatalogHolder();
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry(
                        "harness.worker", "harness", "Worker", true, 0, 1,
                        "目标：{{taskGoal}}\n约束：{{constraints}}\n产出：{{expectedOutput}}\n标准：{{successCriteria}}",
                        null))));
        contextFactory = new WorkerContextFactory(catalogHolder);
        executionProperties = new AgentExecutionProperties();
        executionProperties.getHarness().setEnabled(true);
        asyncToolRunRegistry = new AsyncToolRunRegistry(executionProperties);
        spawnRunRegistry = SpawnRunRegistry.forTest();
        tool = new WorkerDispatchTool(
                agentRuntime, contextFactory, executionProperties, plannerActionTool,
                asyncToolRunRegistry, spawnRunRegistry);
        registry = new StepEventBridgeRegistry();
        StepEventBridge.bindRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        registry.clearAll();
        StepEventBridge.resetRegistry();
        WorkerDispatchTool.clearAllSessionsForTests();
    }

    @Test
    void nameIsDispatchWorker() {
        assertThat(tool.getName()).isEqualTo("dispatch_worker");
    }

    @Test
    void dispatchReturnsRunIdImmediatelyAndCompletesAsync() {
        PlanNotebook nb = PlanNotebook.create("goal", "用户问", "task", 12, 24);
        TaskItem task = new TaskItem("t1", "调研仓库", "pending", List.of(), "只读", "摘要", "有结论",
                "t1", 1, null, null);
        nb.getTaskQueue().add(task);
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb, List.of("sandbox__exec"), "u1", "default", "msg-1", "conv-1",
                "planner-run-1", 40, "chat");
        WorkerDispatchTool.bindSession(session);

        when(agentRuntime.run(any())).thenReturn(Flux.just(
                StreamToken.content("【handoff】\n- 做了什么：扫了结构\n- 结论：ok\n- 未决：无")));

        String result = tool.dispatchWorker("t1", "msg-1");

        // v17.7：dispatch 立即返回 runId，不阻塞
        assertThat(result).contains("\"ok\":true", "\"runId\":", "\"taskId\":\"t1\"", "\"status\":\"running\"");
        // 异步完成后任务终态
        awaitStatus(() -> assertThat(findTask(nb, "t1").status()).isEqualTo("done"));
        assertThat(nb.getRounds()).hasSize(1);
        assertThat(nb.getRounds().get(0).nodeResults()).hasSize(1);
        assertThat(nb.getRounds().get(0).nodeResults().get(0).summary()).contains("【handoff】");

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.role()).isEqualTo(AgentRole.WORKER);
        assertThat(req.toolWhitelist()).containsExactly("sandbox__exec");
        assertThat(req.memory().projectGuideBlock()).contains("调研仓库");
        assertThat(req.query()).contains("用户问");
        assertThat(req.parentRunId()).isEqualTo("planner-run-1");
        // await_tool_run 可收集 DONE 快照
        String runId = extractRunId(result);
        AsyncToolRunRegistry.Snapshot snap = asyncToolRunRegistry.peek(runId);
        assertThat(snap).isNotNull();
        assertThat(snap.status()).isEqualTo(AsyncToolRunRegistry.Status.DONE);
        assertThat(snap.result()).contains("【handoff】");
    }

    @Test
    void dispatchFailsWhenTaskMissing() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        WorkerDispatchTool.bindSession(new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u", "t", "m", "c", "p", 10, "chat"));
        String result = tool.dispatchWorker("nope", "m");
        assertThat(result).contains("\"ok\":false");
        assertThat(result).contains("重试上限");
    }

    @Test
    void dispatchRequiresBoundSession() {
        String result = tool.dispatchWorker("t1", "missing-key");
        assertThat(result).contains("\"ok\":false");
        assertThat(result).contains("未绑定");
    }

    @Test
    void failedWorker_generatesRetryVersionOnReDispatch() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        // t1-1 已失败（timeout）
        nb.getTaskQueue().add(new TaskItem("t1-1", "调研仓库", "fail", List.of(), "只读", "摘要", "有结论",
                "t1", 1, null, "timeout"));
        WorkerDispatchTool.bindSession(new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u", "t", "m", "c", "p", 10, "chat"));
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("retry-ok")));

        // Planner 重派同任务（传 base id t1）
        String result = tool.dispatchWorker("t1", "m");

        assertThat(result).contains("\"ok\":true");
        assertThat(result).contains("\"taskId\":\"t1-2\"");
        // 父执行 t1-1 历史保留（不删除、不回滚）
        assertThat(findTask(nb, "t1-1")).isNotNull();
        assertThat(findTask(nb, "t1-1").status()).isEqualTo("fail");
        assertThat(findTask(nb, "t1-1").failReason()).isEqualTo("timeout");
        // dispatch 同步返回后新版本必须已入队（异步只改状态不改结构）
        assertThat(findTask(nb, "t1-2")).isNotNull();
        awaitStatus(() -> {
            if (findTask(nb, "t1-2") == null) {
                throw new AssertionError("t1-2 不存在, queue=" + nb.snapshotQueue());
            }
            assertThat(findTask(nb, "t1-2").status()).isEqualTo("done");
        });
    }

    @Test
    void retryExhausted_dispatchRejected() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        // t1-1/t1-2/t1-3 全部失败 → 重试上限
        nb.getTaskQueue().add(new TaskItem("t1-1", "调研", "fail", List.of(), "", "", "", "t1", 1, null, "error"));
        nb.getTaskQueue().add(new TaskItem("t1-2", "调研", "fail", List.of(), "", "", "", "t1", 2, "t1-1", "timeout"));
        nb.getTaskQueue().add(new TaskItem("t1-3", "调研", "fail", List.of(), "", "", "", "t1", 3, "t1-2", "error"));
        WorkerDispatchTool.bindSession(new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u", "t", "m", "c", "p", 10, "chat"));

        String result = tool.dispatchWorker("t1", "m");

        assertThat(result).contains("\"ok\":false");
        assertThat(result).contains("重试上限");
        verify(agentRuntime, org.mockito.Mockito.never()).run(any());
    }

    @Test
    void workerError_recordsFailReasonError() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "调研", "pending", List.of(), "", "", "", "t1", 1, null, null));
        WorkerDispatchTool.bindSession(new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u", "t", "m", "c", "p", 10, "chat"));
        when(agentRuntime.run(any()))
                .thenReturn(Flux.error(new IllegalStateException("llm gateway down")));

        String result = tool.dispatchWorker("t1", "m");
        assertThat(result).contains("\"ok\":true");

        awaitStatus(() -> assertThat(findTask(nb, "t1").status()).isEqualTo("fail"));
        assertThat(findTask(nb, "t1").failReason()).isEqualTo("error");
        AsyncToolRunRegistry.Snapshot snap = asyncToolRunRegistry.peek(extractRunId(result));
        assertThat(snap.status()).isEqualTo(AsyncToolRunRegistry.Status.ERROR);
    }

    @Test
    void lookupByPlannerBridgeIdWorksAcrossThreads() throws Exception {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "单元", "pending", List.of(), "", "", "", "t1", 1, null, null));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb, List.of("sandbox__exec"), "u", "t", "msg-x", "c", "run-xyz", 20, "chat");
        WorkerDispatchTool.bindSession(session);

        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("done-handoff")));

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread elastic = new Thread(() -> {
            try {
                started.countDown();
                // 模拟 boundedElastic：不经 ThreadLocal，仅靠 ConcurrentHashMap + bridge key
                result.set(tool.dispatchWorker("t1", "planner-run-xyz"));
            } catch (Throwable t) {
                error.set(t);
            }
        }, "sim-boundedElastic");
        elastic.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        elastic.join(5_000);
        assertThat(error.get()).isNull();
        assertThat(result.get()).contains("\"ok\":true");
        awaitStatus(() -> assertThat(findTask(nb, "t1").status()).isEqualTo("done"));
        assertThat(WorkerDispatchTool.lookupSession("msg-x")).isSameAs(session);
        WorkerDispatchTool.clearSession(session);
        assertThat(WorkerDispatchTool.lookupSession("msg-x")).isNull();
        assertThat(WorkerDispatchTool.lookupSession("planner-run-xyz")).isNull();
    }

    @Test
    void registerIntoToolkitHookExists() {
        io.agentscope.core.tool.Toolkit tk = new io.agentscope.core.tool.Toolkit();
        tool.registerIntoPlannerToolkit(tk);
        assertThat(tk.getToolNames()).contains("dispatch_worker");
    }

    @Test
    void plannerDirectDispatch_foldsWorkerStepsIntoWorkerTimelineCard() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "调研仓库", "pending", List.of(), "只读", "摘要", "有结论",
                "t1", 1, null, null));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb, List.of("sandbox__exec"), "u", "t", "msg-1", "conv-1", "planner-run-1", 40, "chat");
        WorkerDispatchTool.bindSession(session);
        // Planner 直接 dispatch：mainBridge（planner-{parentRunId}）session 存活
        ProcessingTimelineSession plannerSession = new ProcessingTimelineSession();
        ConcurrentLinkedQueue<StreamToken> mainQueue = new ConcurrentLinkedQueue<>();
        StepEventBridge.bind("planner-planner-run-1", plannerSession, mainQueue);

        when(agentRuntime.run(any())).thenAnswer(inv -> {
            AgentRunRequest req = inv.getArgument(0);
            // 模拟 Worker 内部步骤到达 worker-{runId} bridge，触发 PASS_THROUGH wrapper fold
            return Flux.defer(() -> {
                StepEventBridge.offerStreamToken(req.resolveBridgeId(),
                        StreamToken.step(ProcessingStep.running("think", "think", "思考")));
                return Flux.just(StreamToken.content("【handoff】完成调研"));
            });
        });

        String result = tool.dispatchWorker("t1", "msg-1");
        assertThat(result).contains("\"ok\":true");

        // 轮询主时间线直到 worker 卡终态（任务 done 先于 done 步落队，勿以 task 状态作为排空依据）
        AtomicReference<ProcessingStep> terminal = new AtomicReference<>();
        awaitStatus(() -> {
            ProcessingStep card = lastStep(drain(mainQueue), "worker-t1-1");
            assertThat(card).isNotNull();
            assertThat(card.lifecycle()).isEqualTo("done");
            terminal.set(card);
        });
        ProcessingStep workerCard = terminal.get();
        assertThat(workerCard).isNotNull();
        assertThat(workerCard.phase()).isEqualTo("worker");
        assertThat(workerCard.subSteps()).isNotNull();
        assertThat(workerCard.subSteps()).extracting(ProcessingStep::id).contains("think");
        // Worker 正文经 content 路由流式下发到父步 result；终稿由 complete 兜底覆盖
        assertThat(workerCard.result()).isEqualTo("【handoff】完成调研");
        // 不再追加「任务结果汇总」handoff 子步
        assertThat(workerCard.subSteps()).extracting(ProcessingStep::id)
                .noneMatch(id -> id != null && id.endsWith("-handoff"));
    }

    @Test
    void userCancel_stopsSubsequentStreamingFold() throws InterruptedException {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "调研", "pending", List.of(), "", "", "", "t1", 1, null, null));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u", "t", "m", "c", "p", 10, "chat");
        WorkerDispatchTool.bindSession(session);
        ProcessingTimelineSession plannerSession = new ProcessingTimelineSession();
        ConcurrentLinkedQueue<StreamToken> mainQueue = new ConcurrentLinkedQueue<>();
        StepEventBridge.bind("planner-p", plannerSession, mainQueue);

        // Worker 先流式一段正文，随后（取消后）仍尝试继续输出——取消后不得再折叠进主时间线
        when(agentRuntime.run(any())).thenAnswer(inv -> {
            AgentRunRequest req = inv.getArgument(0);
            String bridge = req.resolveBridgeId();
            return Flux.create(sink -> {
                StepEventBridge.offerStreamToken(bridge,
                        StreamToken.step(ProcessingStep.running("think", "think", "思考")));
                StepEventBridge.offerStreamToken(bridge, StreamToken.content("first-part"));
                sleepQuietly(300);
                StepEventBridge.offerStreamToken(bridge, StreamToken.content("second-part-after-cancel"));
                sink.complete();
            });
        });

        String result = tool.dispatchWorker("t1", "m");
        String runId = extractRunId(result);
        assertThat(result).contains("\"ok\":true");

        // 等待首段正文完成折叠，再取消
        awaitStatus(() -> assertThat(joinResultDeltas(mainQueue, "worker-t1-1")).contains("first-part"));
        assertThat(spawnRunRegistry.cancel(runId)).isTrue();
        // 取消终态：TaskItem cancelled + 之后内容不再折叠（p9：取消后停止流式）
        awaitStatus(() -> assertThat(findTask(nb, "t1").status()).isEqualTo("cancelled"));
        Thread.sleep(500);
        assertThat(joinResultDeltas(mainQueue, "worker-t1-1")).doesNotContain("second-part-after-cancel");
    }

    /** 收集 worker 父步 result 通道的 step_delta 增量文本（模拟前端拼接） */
    private static String joinResultDeltas(ConcurrentLinkedQueue<StreamToken> queue, String stepId) {
        return drain(queue).stream()
                .filter(t -> t.isStepDelta() && "result".equals(t.channel()) && stepId.equals(t.stepId()))
                .map(StreamToken::text)
                .collect(Collectors.joining());
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void loopFallback_withoutPlannerSession_doesNotFold() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "调研仓库", "pending", List.of(), "只读", "摘要", "有结论",
                "t1", 1, null, null));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u", "t", "msg-2", "conv-2", "harness-loop-msg-2", 0, "chat");
        WorkerDispatchTool.bindSession(session);
        // Loop 兜底：planner-{loopRunId} 无 session → fold 静默
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("handoff-ok")));

        String result = tool.dispatchWorker("t1", "msg-2");

        assertThat(result).contains("\"ok\":true");
        awaitStatus(() -> assertThat(findTask(nb, "t1").status()).isEqualTo("done"));
    }

    @Test
    void userCancel_immediatelyMarksCancelledAndEmitsBoardSnapshot() throws InterruptedException {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "调研", "pending", List.of(), "", "", "", "t1", 1, null, null));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u", "t", "m", "c", "p", 10, "chat");
        WorkerDispatchTool.bindSession(session);
        // worker 永不完成（Flux.never）：验证取消不经 agent.interrupt 也能即时终态
        when(agentRuntime.run(any())).thenReturn(Flux.never());

        String result = tool.dispatchWorker("t1", "m");
        String runId = extractRunId(result);
        assertThat(spawnRunRegistry.get(runId)).isNotNull();

        boolean cancelled = spawnRunRegistry.cancel(runId);
        assertThat(cancelled).isTrue();
        // onUserCancel：TaskItem cancelled（TaskBoard ⊗）+ async registry CANCELLED
        awaitStatus(() -> assertThat(findTask(nb, "t1").status()).isEqualTo("cancelled"));
        assertThat(findTask(nb, "t1").failReason()).isEqualTo("cancelled");
        AsyncToolRunRegistry.Snapshot snap = asyncToolRunRegistry.peek(runId);
        assertThat(snap.status()).isEqualTo(AsyncToolRunRegistry.Status.CANCELLED);
        verify(plannerActionTool).emitTaskBoardSnapshot(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("worker-cancelled"));
    }


    /** 轮询等待异步终态（最长 5s，100ms 间隔） */
    private static void awaitStatus(Runnable assertion) {
        long deadline = System.currentTimeMillis() + 5_000;
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("await interrupted", ie);
                }
            }
        }
        throw last != null ? last : new AssertionError("await timeout");
    }

    private static String extractRunId(String json) {
        int i = json.indexOf("\"runId\":\"") + "\"runId\":\"".length();
        return json.substring(i, json.indexOf('"', i));
    }

    private static List<StreamToken> drain(ConcurrentLinkedQueue<StreamToken> queue) {
        List<StreamToken> out = new ArrayList<>();
        StreamToken token;
        while ((token = queue.poll()) != null) {
            out.add(token);
        }
        return out;
    }

    private static ProcessingStep lastStep(List<StreamToken> tokens, String stepId) {
        ProcessingStep found = null;
        for (StreamToken token : tokens) {
            if (token.isStep() && token.step() != null && stepId.equals(token.step().id())) {
                found = token.step();
            }
        }
        return found;
    }

    private static TaskItem findTask(PlanNotebook nb, String taskId) {
        return nb.findTask(taskId);
    }
}
