package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlannerHarnessLoopTest {

    @Mock
    private HarnessPlanner planner;
    @Mock
    private WorkerDispatchTool workerDispatchTool;
    @Mock
    private PlanNotebookStore store;
    @Mock
    private ToolSetResolver toolSetResolver;

    private AgentExecutionProperties executionProperties;
    private PlannerHarnessLoop loop;

    @BeforeEach
    void setUp() {
        executionProperties = new AgentExecutionProperties();
        executionProperties.getHarness().setEnabled(true);
        executionProperties.getHarness().getTask().setMaxRetries(2);
        executionProperties.getHarness().getPlanner().setMaxReplans(6);
        when(toolSetResolver.resolveReactTools(any())).thenReturn(List.of("sandbox__exec"));
        loop = new PlannerHarnessLoop(
                planner, workerDispatchTool, store, executionProperties, toolSetResolver);
    }

    @AfterEach
    void tearDown() {
        WorkerDispatchTool.clearAllSessionsForTests();
    }

    @Test
    void stopsWhenMaxRoundsExhaustedAndSynthesizes() {
        PlanNotebook notebook = PlanNotebook.create("goal", "用户问", "task", 2, 24);
        notebook.setSessionId("sess-1");
        AtomicInteger planCalls = new AtomicInteger();
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            int n = planCalls.incrementAndGet();
            nb.getTaskQueue().clear();
            nb.getTaskQueue().add(new TaskItem(
                    "t" + n, "步骤" + n, "pending", List.of(), "", "", ""));
            return null;
        }).when(planner).planNext(any(), any());
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.setGoalCompletion(0.3);
            nb.setNextDirection("continue");
            return null;
        }).when(planner).selfAssess(any(), any());
        doAnswer(inv -> {
            String taskId = inv.getArgument(0);
            WorkerDispatchTool.DispatchSession session = inv.getArgument(1);
            WorkerDispatchTool.replaceTaskStatus(session.notebook(), taskId, "done");
            session.notebook().setTotalTasksCompleted(session.notebook().getTotalTasksCompleted() + 1);
            return "handoff-ok";
        }).when(workerDispatchTool).dispatchWorker(anyString(), any(WorkerDispatchTool.DispatchSession.class));
        when(planner.synthesizeAnswer(any(), any()))
                .thenReturn(Flux.just(StreamToken.content("综合：预算耗尽后的回答")));

        List<StreamToken> tokens = loop.run(streamCtx(), notebook).collectList().block();

        assertThat(tokens).isNotEmpty();
        assertThat(tokens.stream().anyMatch(t -> t.text() != null && t.text().contains("预算耗尽"))).isTrue();
        verify(planner, times(2)).planNext(any(), any());
        verify(planner, times(1)).synthesizeAnswer(any(), any());
        verify(store, atLeast(2)).save(any());
    }

    @Test
    void workerFailAfterRetriesTriggersReplan() {
        PlanNotebook notebook = PlanNotebook.create("goal", "用户问", "task", 12, 24);
        notebook.setSessionId("sess-2");
        AtomicInteger planCalls = new AtomicInteger();
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            int n = planCalls.incrementAndGet();
            nb.getTaskQueue().clear();
            if (n == 1) {
                nb.getTaskQueue().add(new TaskItem(
                        "t-fail", "会失败", "pending", List.of(), "", "", ""));
            } else {
                nb.getTaskQueue().add(new TaskItem(
                        "t-ok", "重规划后成功", "pending", List.of(), "", "", ""));
            }
            return null;
        }).when(planner).planNext(any(), any());
        AtomicInteger dispatchCalls = new AtomicInteger();
        doAnswer(inv -> {
            String taskId = inv.getArgument(0);
            WorkerDispatchTool.DispatchSession session = inv.getArgument(1);
            int n = dispatchCalls.incrementAndGet();
            if ("t-fail".equals(taskId)) {
                WorkerDispatchTool.replaceTaskStatus(session.notebook(), taskId, "fail");
                return "worker-boom-" + n;
            }
            WorkerDispatchTool.replaceTaskStatus(session.notebook(), taskId, "done");
            session.notebook().setTotalTasksCompleted(session.notebook().getTotalTasksCompleted() + 1);
            return "handoff-ok";
        }).when(workerDispatchTool).dispatchWorker(anyString(), any(WorkerDispatchTool.DispatchSession.class));
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            if (nb.getReplanCount() >= 1 && nb.getTotalTasksCompleted() >= 1) {
                nb.setGoalCompletion(1.0);
                nb.setNextDirection("done");
            } else {
                nb.setGoalCompletion(0.2);
                nb.setNextDirection("continue");
            }
            return null;
        }).when(planner).selfAssess(any(), any());
        when(planner.synthesizeAnswer(any(), any()))
                .thenReturn(Flux.just(StreamToken.content("综合：重规划后完成")));

        List<StreamToken> tokens = loop.run(streamCtx(), notebook).collectList().block();

        assertThat(tokens).isNotEmpty();
        // maxRetries=2 → 初始 1 次 + 再跑 2 次 = 3；随后 replan 再跑 t-ok
        assertThat(dispatchCalls.get()).isGreaterThanOrEqualTo(4);
        assertThat(notebook.getReplanCount()).isGreaterThanOrEqualTo(1);
        verify(planner, atLeast(2)).planNext(any(), any());
        verify(planner, times(1)).synthesizeAnswer(any(), any());
    }

    private static ExecutionStreamContext streamCtx() {
        return new ExecutionStreamContext(
                "conv-1",
                "msg-1",
                "用户问",
                AssembledContext.empty(),
                null,
                null,
                "u1",
                "default",
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "harness"));
    }
}
