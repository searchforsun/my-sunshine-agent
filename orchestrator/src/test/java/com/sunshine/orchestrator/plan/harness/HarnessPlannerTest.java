package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextAssembler;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HarnessPlannerTest {

    @Mock
    private AgentRuntime agentRuntime;
    @Mock
    private ContextAssembler contextAssembler;
    @Mock
    private ToolSetResolver toolSetResolver;

    private AgentExecutionProperties executionProperties;
    private HarnessPlanner planner;

    @BeforeEach
    void setUp() {
        executionProperties = new AgentExecutionProperties();
        executionProperties.getHarness().getPlanner().setMaxAttempts(3);
        planner = new HarnessPlanner(agentRuntime, contextAssembler, executionProperties, toolSetResolver);
        org.mockito.Mockito.lenient().when(contextAssembler.assemble(any()))
                .thenReturn(AssembledContext.empty());
        org.mockito.Mockito.lenient().when(toolSetResolver.resolveReactTools(eq("default")))
                .thenReturn(List.of("sandbox__exec", "search_knowledge"));
    }

    @AfterEach
    void tearDown() {
        WorkerDispatchTool.clearAllSessionsForTests();
    }

    @Test
    void planNext_writesTaskQueueFromPlannerJson() {
        PlanNotebook nb = PlanNotebook.create("完成调研", "帮我调研仓库", "task", 12, 24);
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content(planJson(
                "t1", "摸底代码库", "只读", "结构摘要", "有目录图"))));

        planner.planNext(nb, streamCtx());

        assertThat(nb.getTaskQueue()).hasSize(1);
        TaskItem task = nb.getTaskQueue().peek();
        assertThat(task.taskId()).isEqualTo("t1");
        assertThat(task.label()).isEqualTo("摸底代码库");
        assertThat(task.status()).isEqualTo("pending");
        assertThat(task.constraints()).isEqualTo("只读");
        assertThat(task.expectedOutput()).isEqualTo("结构摘要");
        assertThat(task.successCriteria()).isEqualTo("有目录图");
        assertThat(nb.getRounds()).isEmpty();
    }

    @Test
    void planNext_retriesInvalidJsonUntilSuccess() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        AtomicInteger calls = new AtomicInteger();
        when(agentRuntime.run(any())).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                return Flux.just(StreamToken.content("not-json"));
            }
            return Flux.just(StreamToken.content(planJson("t2", "第二步", "", "", "")));
        });

        planner.planNext(nb, streamCtx());

        assertThat(calls.get()).isEqualTo(3);
        assertThat(nb.getTaskQueue()).hasSize(1);
        assertThat(nb.getTaskQueue().peek().taskId()).isEqualTo("t2");
    }

    @Test
    void planNext_throwsAfterMaxAttempts() {
        executionProperties.getHarness().getPlanner().setMaxAttempts(2);
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("<<<")));

        assertThatThrownBy(() -> planner.planNext(nb, streamCtx()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planNext");
        verify(agentRuntime, times(2)).run(any());
        assertThat(nb.getTaskQueue()).isEmpty();
    }

    @Test
    void planNext_injectsH1AndPlannerHarnessCatalogId() {
        PlanNotebook nb = PlanNotebook.create("完成调研", "帮我调研仓库", "task", 12, 24);
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content(planJson(
                "t1", "摸底", "", "", ""))));

        planner.planNext(nb, streamCtx());

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.role()).isEqualTo(AgentRole.PLANNER);
        assertThat(req.reactPromptId()).isEqualTo(HarnessPlanner.CATALOG_ID);
        assertThat(req.injectedBlocks()).isNotEmpty();
        assertThat(req.injectedBlocks().get(0)).contains("## Goal").contains("完成调研");
        assertThat(WorkerDispatchTool.currentSession("msg-1")).isNull();
        verify(toolSetResolver).resolveReactTools("default");
    }

    @Test
    void planNext_bindsWorkerWhitelistFromToolSetResolver() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        AtomicReference<WorkerDispatchTool.DispatchSession> duringRun = new AtomicReference<>();
        when(agentRuntime.run(any())).thenAnswer(inv -> {
            duringRun.set(WorkerDispatchTool.currentSession("msg-1"));
            return Flux.just(StreamToken.content(planJson("t1", "步", "", "", "")));
        });

        planner.planNext(nb, streamCtx());

        assertThat(duringRun.get()).isNotNull();
        assertThat(duringRun.get().toolWhitelist()).containsExactly("sandbox__exec", "search_knowledge");
        assertThat(WorkerDispatchTool.currentSession("msg-1")).isNull();
    }

    @Test
    void selfAssess_writesGoalCompletionAndNextDirection() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content(
                "{\"action\":\"selfAssess\",\"goalCompletion\":0.4,\"reason\":\"调研未完\",\"nextDirection\":\"continue\"}")));

        planner.selfAssess(nb, streamCtx());

        assertThat(nb.getGoalCompletion()).isEqualTo(0.4);
        assertThat(nb.getNextDirection()).isEqualTo("continue");
        assertThat(nb.getRounds()).isEmpty();
    }

    @Test
    void synthesizeAnswer_streamsContentTokens() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        when(agentRuntime.run(any())).thenReturn(Flux.just(
                StreamToken.content("综合结论：仓库结构清晰。")));

        List<StreamToken> tokens = planner.synthesizeAnswer(nb, streamCtx()).collectList().block();

        assertThat(tokens).isNotEmpty();
        assertThat(tokens.get(0).text()).contains("综合结论");
        assertThat(nb.getRounds()).isEmpty();
        assertThat(WorkerDispatchTool.currentSession("msg-1")).isNull();
    }

    private static ExecutionStreamContext streamCtx() {
        return new ExecutionStreamContext(
                "conv-1",
                "msg-1",
                "帮我调研仓库",
                AssembledContext.empty(),
                null,
                null,
                "u1",
                "default",
                new ExecutionPlan(ExecutionMode.PRO, null, Map.of(), "harness"));
    }

    private static String planJson(
            String taskId, String label, String constraints, String expectedOutput, String successCriteria) {
        return "{\"action\":\"plan\",\"reason\":\"先调研\",\"tasks\":[{"
                + "\"taskId\":\"" + taskId + "\","
                + "\"label\":\"" + label + "\","
                + "\"dependsOn\":[],"
                + "\"constraints\":\"" + constraints + "\","
                + "\"expectedOutput\":\"" + expectedOutput + "\","
                + "\"successCriteria\":\"" + successCriteria + "\"}]}";
    }
}
