package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import com.sunshine.orchestrator.plan.PlanValidator;
import com.sunshine.orchestrator.plan.WorkflowPlanner;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanWorkflowExecutorTest {

    @Mock
    private WorkflowPlanner workflowPlanner;
    @Mock
    private PlanValidator planValidator;
    @Mock
    private PlanDisplayNameEnricher displayNameEnricher;
    @Mock
    private PlanMaterializer planMaterializer;
    @Mock
    private WorkflowExecutor workflowExecutor;
    @Mock
    private ReactExecutor reactExecutor;
    @Mock
    private ExecutionPlanStore executionPlanStore;
    @InjectMocks
    private PlanWorkflowExecutor planWorkflowExecutor;

    @Test
    void execute_runsDynamicWorkflowWhenPlanValid() {
        ExecutionStreamContext ctx = context();
        PlanJson planJson = new PlanJson("p1", "r",
                List.of(new PlanNodeStub().toPlanNode()),
                List.of());
        WorkflowDefinition def = WorkflowDefinition.from("p1",
                List.of(new com.sunshine.orchestrator.execution.NodeSpec("n1", "llm", Map.of())),
                List.of("n1"));

        when(executionPlanStore.createDraft(eq(ctx), any(PlanJson.class))).thenReturn("plan-db-1");
        when(workflowPlanner.plan(ctx)).thenReturn(Mono.just(planJson));
        when(displayNameEnricher.enrich(any(PlanJson.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planValidator.validate(any(PlanJson.class))).thenReturn(null);
        when(planMaterializer.materialize(any(PlanJson.class))).thenReturn(def);
        when(workflowExecutor.executeDynamicDefinition(eq(def), any(ExecutionStreamContext.class)))
                .thenReturn(Flux.just(StreamToken.content("done")));

        List<StreamToken> tokens = planWorkflowExecutor.execute(ctx).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(tokens.stream().anyMatch(t -> t.isStep() || t.isContent())).isTrue();
        verify(executionPlanStore).markValidated(eq("plan-db-1"), any(PlanJson.class));
        verify(executionPlanStore).markRunning("plan-db-1");
        verify(executionPlanStore).markCompleted("plan-db-1");
        verify(workflowExecutor).executeDynamicDefinition(eq(def), any(ExecutionStreamContext.class));
    }

    @Test
    void execute_fallsBackToReactWhenValidationFails() {
        ExecutionStreamContext ctx = context();
        PlanJson invalidPlan = new PlanJson("p1", "r",
                List.of(new PlanNodeStub().toPlanNode()),
                List.of());
        when(executionPlanStore.createDraft(eq(ctx), any(PlanJson.class))).thenReturn("plan-db-1");
        when(workflowPlanner.plan(ctx)).thenReturn(Mono.just(invalidPlan));
        when(displayNameEnricher.enrich(any(PlanJson.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planValidator.validate(any(PlanJson.class))).thenReturn("未知工具: bad-tool");
        when(reactExecutor.execute(ctx)).thenReturn(Flux.just(StreamToken.content("react")));

        planWorkflowExecutor.execute(ctx).collectList().block();
        verify(executionPlanStore).markRejected("plan-db-1", "未知工具: bad-tool");
        verify(reactExecutor).execute(ctx);
    }

    @Test
    void execute_fallsBackToReactWhenPlannerFails() {
        ExecutionStreamContext ctx = context();
        when(workflowPlanner.plan(ctx)).thenReturn(Mono.error(new RuntimeException("llm down")));
        when(reactExecutor.execute(ctx)).thenReturn(Flux.just(StreamToken.content("react")));

        List<StreamToken> tokens = planWorkflowExecutor.execute(ctx).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(tokens.stream().filter(StreamToken::isStep).count()).isGreaterThanOrEqualTo(1);
        verify(reactExecutor).execute(ctx);
    }

    private static ExecutionStreamContext context() {
        return new ExecutionStreamContext(
                "c1", "msg-1", "跨领域任务", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "test"));
    }

    private static final class PlanNodeStub {
        com.sunshine.orchestrator.plan.PlanNode toPlanNode() {
            return new com.sunshine.orchestrator.plan.PlanNode("n1", "llm", Map.of());
        }
    }
}
