package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.plan.ExecutionPlanEntity;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PausePhase;
import com.sunshine.orchestrator.plan.PlanApprovalService;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanJsonParser;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import com.sunshine.orchestrator.plan.PlanNormalizer;
import com.sunshine.orchestrator.plan.PlanRunFinalizer;
import com.sunshine.orchestrator.plan.PlanValidator;
import com.sunshine.orchestrator.plan.WorkflowCheckpoint;
import com.sunshine.orchestrator.plan.WorkflowPlanner;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanWorkflowExecutorResumeTest {

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
    @Mock
    private PlanExecutionAuditService planExecutionAuditService;
    @Mock
    private PlanJsonParser planJsonParser;
    @Mock
    private PlanApprovalService planApprovalService;

    private PlanWorkflowExecutor executor;
    private AgentExecutionProperties executionProperties;

    @BeforeEach
    void setUp() {
        executionProperties = new AgentExecutionProperties();
        PlanRunFinalizer finalizer = new PlanRunFinalizer(
                executionPlanStore, planExecutionAuditService, executionProperties, reactExecutor);
        when(planApprovalService.isEnabled()).thenReturn(false);
        executor = new PlanWorkflowExecutor(
                workflowPlanner, planValidator, displayNameEnricher, planMaterializer,
                workflowExecutor, reactExecutor, executionPlanStore, executionProperties,
                planExecutionAuditService, finalizer, planJsonParser, planApprovalService,
                new AgentPauseProperties(), new WorkflowPauseService());
    }

    private void stubPlanStoreCheckpoint(ExecutionPlanEntity entity, WorkflowCheckpoint checkpoint) {
        when(executionPlanStore.loadCheckpoint(entity)).thenReturn(checkpoint);
    }

    @Test
    void resumePaused_planningWithValidated_skipsPlanner() {
        ExecutionStreamContext ctx = context();
        ExecutionPlanEntity entity = pausedEntity(validatedPlanJson(), PausePhase.PLANNING);
        PlanJson planJson = samplePlan();
        WorkflowDefinition def = WorkflowDefinition.from("p1",
                List.of(new NodeSpec("n1", "llm", Map.of())), List.of("n1"));

        when(executionPlanStore.loadCheckpoint(any(ExecutionPlanEntity.class))).thenReturn(
                new WorkflowCheckpoint("n1", "{}", PausePhase.PLANNING, null));
        when(planJsonParser.parse(any())).thenReturn(planJson);
        when(displayNameEnricher.enrich(any(PlanJson.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planMaterializer.materialize(any(PlanJson.class))).thenReturn(def);
        when(workflowExecutor.executeDynamicDefinition(eq(def), any(), any()))
                .thenReturn(Flux.just(StreamToken.content("done")));

        executor.resumePaused(ctx, entity).collectList().block();

        verify(workflowPlanner, never()).plan(any());
        verify(workflowExecutor).executeDynamicDefinition(eq(def), any(), any());
    }

    @Test
    void resumePaused_planningWithoutValidated_replans() {
        ExecutionStreamContext ctx = context();
        ExecutionPlanEntity entity = pausedEntity(null, PausePhase.PLANNING);
        PlanJson planJson = samplePlan();
        WorkflowDefinition def = WorkflowDefinition.from("p1",
                List.of(new NodeSpec("n1", "llm", Map.of())), List.of("n1"));

        when(executionPlanStore.loadCheckpoint(any(ExecutionPlanEntity.class))).thenReturn(
                new WorkflowCheckpoint("", "{}", PausePhase.PLANNING, null));
        when(workflowPlanner.plan(ctx)).thenReturn(Mono.just(planJson));
        when(planValidator.validatePlannerOutput(any(PlanJson.class))).thenReturn(null);
        when(displayNameEnricher.enrich(any(PlanJson.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planValidator.validate(any(PlanJson.class))).thenReturn(null);
        when(planMaterializer.materialize(any(PlanJson.class))).thenReturn(def);
        when(workflowExecutor.executeDynamicDefinition(eq(def), any(), any()))
                .thenReturn(Flux.just(StreamToken.content("done")));

        executor.resumePaused(ctx, entity).collectList().block();

        verify(workflowPlanner).plan(ctx);
    }

    private static ExecutionPlanEntity pausedEntity(String validatedJson, PausePhase phase) {
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        entity.setId("p1");
        entity.setStatus("paused");
        entity.setValidatedJson(validatedJson);
        entity.setPauseCheckpoint("{\"resumeNodeId\":\"n1\",\"wfCtxJson\":\"{}\",\"pausePhase\":\""
                + phase.name() + "\"}");
        return entity;
    }

    private static String validatedPlanJson() {
        return "{\"planId\":\"p1\",\"nodes\":[{\"id\":\"n1\",\"type\":\"llm\"}],\"edges\":[]}";
    }

    private static PlanJson samplePlan() {
        return PlanNormalizer.normalize(new PlanJson("p1", "r",
                List.of(
                        new com.sunshine.orchestrator.plan.PlanNode("start", "start", Map.of()),
                        new com.sunshine.orchestrator.plan.PlanNode("n1", "llm", Map.of()),
                        new com.sunshine.orchestrator.plan.PlanNode("answer", "answer", Map.of())),
                List.of(
                        new com.sunshine.orchestrator.plan.PlanEdge("start", "n1"),
                        new com.sunshine.orchestrator.plan.PlanEdge("n1", "answer"))));
    }

    private static ExecutionStreamContext context() {
        return new ExecutionStreamContext(
                "c1", "msg-1", "query", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "test"));
    }
}
