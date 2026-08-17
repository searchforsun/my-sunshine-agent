package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.StepEventBridgeRegistry;
import com.sunshine.orchestrator.agent.TokenWrapperMode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerDispatchToolTest {

    @Mock
    private AgentRuntime agentRuntime;

    private PromptCatalogHolder catalogHolder;
    private WorkerContextFactory contextFactory;
    private AgentExecutionProperties executionProperties;
    private WorkerDispatchTool tool;
    private StepEventBridgeRegistry registry;

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
        tool = new WorkerDispatchTool(agentRuntime, contextFactory, executionProperties);
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
    void dispatchRunsWorkerAndUpdatesNotebook() {
        PlanNotebook nb = PlanNotebook.create("goal", "用户问", "task", 12, 24);
        TaskItem task = new TaskItem("t1", "调研仓库", "pending", List.of(), "只读", "摘要", "有结论");
        nb.getTaskQueue().add(task);
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb,
                List.of("sandbox__exec"),
                "u1",
                "default",
                "msg-1",
                "conv-1",
                "planner-run-1",
                40,
                "chat",
                WorkerDispatchTool.DispatchSession.ActionSignals.fresh());
        WorkerDispatchTool.bindSession(session);

        when(agentRuntime.run(any())).thenReturn(Flux.just(
                StreamToken.content("【handoff】\n- 做了什么：扫了结构\n- 结论：ok\n- 未决：无")));

        String handoff = tool.dispatchWorker("t1", "msg-1");

        assertThat(handoff).contains("【handoff】", "ok");
        TaskItem updated = findTask(nb, "t1");
        assertThat(updated.status()).isEqualTo("done");
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
    }

    @Test
    void dispatchFailsWhenTaskMissing() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        WorkerDispatchTool.bindSession(new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u", "t", "m", "c", "p", 10, "chat",
                WorkerDispatchTool.DispatchSession.ActionSignals.fresh()));
        String result = tool.dispatchWorker("nope", "m");
        assertThat(result).contains("\"ok\":false");
        assertThat(result).contains("未找到任务");
    }

    @Test
    void dispatchRequiresBoundSession() {
        String result = tool.dispatchWorker("t1", "missing-key");
        assertThat(result).contains("\"ok\":false");
        assertThat(result).contains("未绑定");
    }

    @Test
    void lookupByPlannerBridgeIdWorksAcrossThreads() throws Exception {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "单元", "pending", List.of(), "", "", ""));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb, List.of("sandbox__exec"), "u", "t", "msg-x", "c", "run-xyz", 20, "chat",
                WorkerDispatchTool.DispatchSession.ActionSignals.fresh());
        WorkerDispatchTool.bindSession(session);

        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("done-handoff")));

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> handoff = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread elastic = new Thread(() -> {
            try {
                started.countDown();
                // 模拟 boundedElastic：不经 ThreadLocal，仅靠 ConcurrentHashMap + bridge key
                handoff.set(tool.dispatchWorker("t1", "planner-run-xyz"));
            } catch (Throwable t) {
                error.set(t);
            }
        }, "sim-boundedElastic");
        elastic.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        elastic.join(5_000);
        assertThat(error.get()).isNull();
        assertThat(handoff.get()).contains("done-handoff");
        assertThat(findTask(nb, "t1").status()).isEqualTo("done");
        assertThat(WorkerDispatchTool.currentSession("msg-x")).isSameAs(session);
        WorkerDispatchTool.clearSession(session);
        assertThat(WorkerDispatchTool.currentSession("msg-x")).isNull();
        assertThat(WorkerDispatchTool.currentSession("planner-run-xyz")).isNull();
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
        nb.getTaskQueue().add(new TaskItem("t1", "调研仓库", "pending", List.of(), "只读", "摘要", "有结论"));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb,
                List.of("sandbox__exec"),
                "u",
                "t",
                "msg-1",
                "conv-1",
                "planner-run-1",
                40,
                "chat",
                WorkerDispatchTool.DispatchSession.ActionSignals.fresh());
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

        String handoff = tool.dispatchWorker("t1", "msg-1");

        assertThat(handoff).contains("handoff");
        List<StreamToken> mainTokens = drain(mainQueue);
        ProcessingStep workerCard = lastStep(mainTokens, "worker-t1");
        assertThat(workerCard).isNotNull();
        assertThat(workerCard.phase()).isEqualTo("worker");
        assertThat(workerCard.subSteps()).isNotNull();
        assertThat(workerCard.subSteps()).extracting(ProcessingStep::id).contains("think");
        assertThat(workerCard.lifecycle()).isEqualTo("done");
        assertThat(workerCard.result()).contains("handoff");
    }

    @Test
    void loopFallback_withoutPlannerSession_doesNotFold() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "调研仓库", "pending", List.of(), "只读", "摘要", "有结论"));
        WorkerDispatchTool.DispatchSession session = new WorkerDispatchTool.DispatchSession(
                nb,
                List.of(),
                "u",
                "t",
                "msg-2",
                "conv-2",
                "harness-loop-msg-2",
                0,
                "chat",
                WorkerDispatchTool.DispatchSession.ActionSignals.fresh());
        WorkerDispatchTool.bindSession(session);
        // Loop 兜底：planner-{loopRunId} 无 session → fold 静默
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("handoff-ok")));

        String handoff = tool.dispatchWorker("t1", "msg-2");

        assertThat(handoff).contains("handoff-ok");
        assertThat(findTask(nb, "t1").status()).isEqualTo("done");
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
        Deque<TaskItem> copy = new ArrayDeque<>(nb.getTaskQueue());
        for (TaskItem item : copy) {
            if (taskId.equals(item.taskId())) {
                return item;
            }
        }
        return null;
    }
}
