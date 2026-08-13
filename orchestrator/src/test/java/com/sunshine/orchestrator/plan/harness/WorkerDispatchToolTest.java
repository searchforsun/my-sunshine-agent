package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
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
import java.util.Deque;
import java.util.List;
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
    }

    @AfterEach
    void tearDown() {
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
                40);
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
                nb, List.of(), "u", "t", "m", "c", "p", 10));
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
                nb, List.of("sandbox__exec"), "u", "t", "msg-x", "c", "run-xyz", 20);
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
