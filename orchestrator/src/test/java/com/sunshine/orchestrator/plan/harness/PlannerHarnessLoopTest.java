package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.agent.ProcessingStep;
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
    @Mock
    private PlanExecutionAuditService planExecutionAuditService;

    private AgentExecutionProperties executionProperties;
    private PlannerHarnessLoop loop;

    @BeforeEach
    void setUp() {
        executionProperties = new AgentExecutionProperties();
        executionProperties.getHarness().setEnabled(true);
        executionProperties.getHarness().getTask().setMaxRetries(2);
        executionProperties.getHarness().getPlanner().setMaxReplans(6);
        org.mockito.Mockito.lenient().when(toolSetResolver.resolveDefaultTools(any(), any()))
                .thenReturn(List.of("sandbox__exec"));
        loop = new PlannerHarnessLoop(
                planner, workerDispatchTool, store, executionProperties, toolSetResolver,
                planExecutionAuditService);
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

    @Test
    void nextDirectionAnswerSynthesizesEvenIfCompletionBelowOne() {
        PlanNotebook notebook = PlanNotebook.create("goal", "用户问", "task", 12, 24);
        notebook.setSessionId("sess-answer");
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.getTaskQueue().clear();
            nb.getTaskQueue().add(new TaskItem(
                    "t1", "一步", "pending", List.of(), "", "", ""));
            return null;
        }).when(planner).planNext(any(), any());
        doAnswer(inv -> {
            String taskId = inv.getArgument(0);
            WorkerDispatchTool.DispatchSession session = inv.getArgument(1);
            WorkerDispatchTool.replaceTaskStatus(session.notebook(), taskId, "done");
            session.notebook().setTotalTasksCompleted(1);
            assertThat(session.parentRunId()).startsWith("harness-loop-");
            return "ok";
        }).when(workerDispatchTool).dispatchWorker(anyString(), any(WorkerDispatchTool.DispatchSession.class));
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.setGoalCompletion(0.7);
            nb.setNextDirection("answer");
            return null;
        }).when(planner).selfAssess(any(), any());
        when(planner.synthesizeAnswer(any(), any()))
                .thenReturn(Flux.just(StreamToken.content("综合：自判回答")));

        List<StreamToken> tokens = loop.run(streamCtx(), notebook).collectList().block();

        assertThat(tokens.stream().anyMatch(t -> t.text() != null && t.text().contains("自判回答"))).isTrue();
        verify(planner, times(1)).planNext(any(), any());
        verify(planner, times(1)).synthesizeAnswer(any(), any());
    }

    @Test
    void goalAlignmentDeviatedRunsWorkerThenReplans() {
        PlanNotebook notebook = PlanNotebook.create("goal", "用户问", "task", 12, 24);
        notebook.setSessionId("sess-deviated");
        // 冷启动后首波完成任务但自判完成度极低 → Assess 后 DEVIATED；不得饿死首波 Execute
        AtomicInteger planCalls = new AtomicInteger();
        AtomicInteger dispatchCalls = new AtomicInteger();
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            int n = planCalls.incrementAndGet();
            nb.getTaskQueue().clear();
            nb.getTaskQueue().add(new TaskItem(
                    "t" + n, "步骤" + n, "pending", List.of(), "", "", ""));
            return null;
        }).when(planner).planNext(any(), any());
        doAnswer(inv -> {
            String taskId = inv.getArgument(0);
            WorkerDispatchTool.DispatchSession session = inv.getArgument(1);
            dispatchCalls.incrementAndGet();
            WorkerDispatchTool.replaceTaskStatus(session.notebook(), taskId, "done");
            session.notebook().setTotalTasksCompleted(session.notebook().getTotalTasksCompleted() + 1);
            return "ok";
        }).when(workerDispatchTool).dispatchWorker(anyString(), any(WorkerDispatchTool.DispatchSession.class));
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            if (nb.getReplanCount() >= 1) {
                nb.setGoalCompletion(1.0);
                nb.setNextDirection("answer");
            } else {
                nb.setGoalCompletion(0.05);
                nb.setNextDirection("continue");
            }
            return null;
        }).when(planner).selfAssess(any(), any());
        when(planner.synthesizeAnswer(any(), any()))
                .thenReturn(Flux.just(StreamToken.content("综合：偏离后重规划完成")));

        List<StreamToken> tokens = loop.run(streamCtx(), notebook).collectList().block();

        assertThat(dispatchCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(notebook.getReplanCount()).isGreaterThanOrEqualTo(1);
        assertThat(tokens.stream().anyMatch(t -> t.text() != null && t.text().contains("偏离后"))).isTrue();
        verify(planner, atLeast(2)).planNext(any(), any());
    }

    @Test
    void stopsWhenMaxReplansExhaustedAndSynthesizes() {
        executionProperties.getHarness().getPlanner().setMaxReplans(2);
        executionProperties.getHarness().setMaxRounds(12);
        PlanNotebook notebook = PlanNotebook.create("goal", "用户问", "task", 12, 24);
        notebook.setSessionId("sess-replans");
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.getTaskQueue().clear();
            nb.getTaskQueue().add(new TaskItem(
                    "t-replan", "总要重规划", "pending", List.of(), "", "", ""));
            return null;
        }).when(planner).planNext(any(), any());
        doAnswer(inv -> {
            String taskId = inv.getArgument(0);
            WorkerDispatchTool.DispatchSession session = inv.getArgument(1);
            WorkerDispatchTool.replaceTaskStatus(session.notebook(), taskId, "done");
            session.notebook().setTotalTasksCompleted(session.notebook().getTotalTasksCompleted() + 1);
            return "ok";
        }).when(workerDispatchTool).dispatchWorker(anyString(), any(WorkerDispatchTool.DispatchSession.class));
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.setGoalCompletion(0.2);
            nb.setNextDirection("replan");
            return null;
        }).when(planner).selfAssess(any(), any());
        when(planner.synthesizeAnswer(any(), any()))
                .thenReturn(Flux.just(StreamToken.content("综合：maxReplans 熔断")));

        List<StreamToken> tokens = loop.run(streamCtx(), notebook).collectList().block();

        assertThat(tokens.stream().anyMatch(t -> t.text() != null && t.text().contains("maxReplans"))).isTrue();
        assertThat(notebook.getReplanCount()).isGreaterThanOrEqualTo(2);
        verify(planner, times(1)).synthesizeAnswer(any(), any());
    }

    @Test
    void resolveAssessDecision_mapsCatalogDirections() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setGoalCompletion(0.5);
        nb.setNextDirection("answer");
        assertThat(PlannerHarnessLoop.resolveAssessDecision(nb))
                .isEqualTo(PlannerHarnessLoop.AssessDecision.ANSWER);
        nb.setNextDirection("replan");
        assertThat(PlannerHarnessLoop.resolveAssessDecision(nb))
                .isEqualTo(PlannerHarnessLoop.AssessDecision.REPLAN);
        nb.setNextDirection("continue");
        assertThat(PlannerHarnessLoop.resolveAssessDecision(nb))
                .isEqualTo(PlannerHarnessLoop.AssessDecision.CONTINUE);
        nb.setNextDirection(null);
        nb.setGoalCompletion(1.0);
        assertThat(PlannerHarnessLoop.resolveAssessDecision(nb))
                .isEqualTo(PlannerHarnessLoop.AssessDecision.ANSWER);
    }

    @Test
    void emitsTasksStepAfterPlanWithMetadata() {
        PlanNotebook notebook = PlanNotebook.create("goal", "用户问", "task", 12, 24);
        notebook.setSessionId("sess-tasks");
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.getTaskQueue().clear();
            nb.getTaskQueue().add(new TaskItem(
                    "t1", "一步", "pending", List.of(), "", "", ""));
            return null;
        }).when(planner).planNext(any(), any());
        doAnswer(inv -> {
            String taskId = inv.getArgument(0);
            WorkerDispatchTool.DispatchSession session = inv.getArgument(1);
            WorkerDispatchTool.replaceTaskStatus(session.notebook(), taskId, "done");
            session.notebook().setTotalTasksCompleted(1);
            return "handoff：完成摘要";
        }).when(workerDispatchTool).dispatchWorker(anyString(), any(WorkerDispatchTool.DispatchSession.class));
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.setGoalCompletion(1.0);
            nb.setNextDirection("answer");
            return null;
        }).when(planner).selfAssess(any(), any());
        when(planner.synthesizeAnswer(any(), any()))
                .thenReturn(Flux.just(StreamToken.content("综合")));

        List<StreamToken> tokens = loop.run(streamCtx(), notebook).collectList().block();
        ProcessingStep tasks = tokens.stream()
                .filter(StreamToken::isStep)
                .map(StreamToken::step)
                .filter(s -> "tasks".equals(s.id()))
                .findFirst()
                .orElse(null);
        assertThat(tasks).isNotNull();
        assertThat(tasks.phase()).isEqualTo("tasks");
        assertThat(tasks.metadata()).isNotNull();
        assertThat(tasks.metadata().tasks()).isNotEmpty();
        assertThat(tasks.metadata().taskRevision()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void workerDoneStepCarriesHandoffNotBareStatus() {
        PlanNotebook notebook = PlanNotebook.create("goal", "用户问", "task", 12, 24);
        notebook.setSessionId("sess-handoff");
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.getTaskQueue().clear();
            nb.getTaskQueue().add(new TaskItem(
                    "t1", "一步", "pending", List.of(), "", "", ""));
            return null;
        }).when(planner).planNext(any(), any());
        doAnswer(inv -> {
            String taskId = inv.getArgument(0);
            WorkerDispatchTool.DispatchSession session = inv.getArgument(1);
            WorkerDispatchTool.replaceTaskStatus(session.notebook(), taskId, "done");
            session.notebook().setTotalTasksCompleted(1);
            return "handoff：完成摘要";
        }).when(workerDispatchTool).dispatchWorker(anyString(), any(WorkerDispatchTool.DispatchSession.class));
        doAnswer(inv -> {
            PlanNotebook nb = inv.getArgument(0);
            nb.setGoalCompletion(1.0);
            nb.setNextDirection("answer");
            return null;
        }).when(planner).selfAssess(any(), any());
        when(planner.synthesizeAnswer(any(), any()))
                .thenReturn(Flux.just(StreamToken.content("综合")));

        List<StreamToken> tokens = loop.run(streamCtx(), notebook).collectList().block();
        ProcessingStep w = tokens.stream()
                .filter(StreamToken::isStep)
                .map(StreamToken::step)
                .filter(s -> "worker-t1".equals(s.id()))
                .filter(s -> "done".equals(s.lifecycle()))
                .findFirst()
                .orElse(null);
        assertThat(w).isNotNull();
        assertThat(w.detail()).isNotEqualTo("done");
        assertThat(w.detail()).contains("摘要");
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
                new ExecutionPlan(ExecutionMode.PRO, null, Map.of(), "harness"));
    }
}
