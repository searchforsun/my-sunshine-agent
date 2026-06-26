package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentGroundingProperties;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.grounding.GroundingVerdict;
import com.sunshine.orchestrator.execution.handler.AnswerNodeHandler;
import com.sunshine.orchestrator.execution.handler.RagNodeHandler;
import com.sunshine.orchestrator.execution.handler.StartNodeHandler;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanRunFinalizer;
import com.sunshine.orchestrator.plan.PlanTimeline;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowExecutorTest {

    @Mock
    private WorkflowDefinitionLoader loader;

    @Mock
    private NodeHandlerRegistry registry;

    @Mock
    private StartNodeHandler startNodeHandler;

    @Mock
    private RagNodeHandler ragNodeHandler;

    @Mock
    private AnswerNodeHandler answerNodeHandler;

    @Mock
    private ExecutionPlanStore executionPlanStore;

    @Mock
    private WorkflowNodeLabelService labelService;

    @Mock
    private com.sunshine.orchestrator.execution.retry.NodeRetryPolicyResolver retryPolicyResolver;

    @Mock
    private com.sunshine.orchestrator.execution.retry.NodeRetryExecutor nodeRetryExecutor;

    @Mock
    private UpstreamOutputResolver upstreamOutputResolver;

    @Mock
    private PlanExecutionAuditService planExecutionAuditService;

    @Mock
    private PlanDisplayNameEnricher displayNameEnricher;

    @Mock
    private PlanRunFinalizer planRunFinalizer;

    @Mock
    private AnswerGroundingChecker groundingChecker;

    @Mock
    private com.sunshine.orchestrator.hitl.WorkflowNodeRecoveryService workflowNodeRecoveryService;

    @Mock
    private WorkflowPauseService workflowPauseService;

    @Mock
    private com.sunshine.orchestrator.generation.GenerationRegistry generationRegistry;

    private AgentGroundingProperties groundingProperties;

    @InjectMocks
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        when(retryPolicyResolver.resolve(any(), any(Boolean.class)))
                .thenReturn(com.sunshine.orchestrator.execution.retry.NodeRetryPolicy.noRetry(
                        com.sunshine.orchestrator.execution.retry.OnFailureAction.CONTINUE));
        when(nodeRetryExecutor.runWithRetry(any(), any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    java.util.function.Supplier<Mono<NodeResult>> supplier = inv.getArgument(1);
                    return supplier.get().map(result ->
                            new com.sunshine.orchestrator.execution.retry.NodeRetryExecutor.AttemptOutcome(
                                    result, List.of()));
                });
        when(upstreamOutputResolver.resolvePrompt(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(registry.require("start")).thenReturn(startNodeHandler);
        when(registry.require("rag")).thenReturn(ragNodeHandler);
        when(registry.require("answer")).thenReturn(answerNodeHandler);

        when(startNodeHandler.run(any(), any(), any()))
                .thenReturn(Mono.just(NodeResult.ok(Map.of("userQuery", "请假制度"))));
        when(ragNodeHandler.run(any(), any(), any()))
                .thenReturn(Mono.just(NodeResult.ok(Map.of(
                        "output", "rag-hit", "detail", "命中 1 条", "hitCount", "1"))));
        when(answerNodeHandler.createStreamCollector(any(), any()))
                .thenReturn(new WorkflowStreamCollector());
        when(answerNodeHandler.streamTokens(any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(StreamToken.content("请假需提前申请")));
        when(answerNodeHandler.streamTokens(any(), any(), any(), any()))
                .thenReturn(Flux.just(StreamToken.content("请假需提前申请")));
        when(answerNodeHandler.buildResult(any()))
                .thenReturn(NodeResult.ok(Map.of(
                        "answer", "请假需提前申请",
                        "output", "请假需提前申请",
                        "reasoning", "先核对制度条款再归纳要点",
                        "detail", "先核对制度条款再归纳要点")));

        when(executionPlanStore.createDraft(any(), any(PlanJson.class))).thenReturn("static-plan-1");
        when(displayNameEnricher.enrich(any(PlanJson.class))).thenAnswer(inv -> inv.getArgument(0));
        when(planRunFinalizer.postWorkflow(any(), eq("static-plan-1"), any()))
                .thenReturn(Flux.empty());
        when(groundingChecker.check(any(), any())).thenReturn(GroundingVerdict.pass());
        groundingProperties = new AgentGroundingProperties();
        groundingProperties.setEnabled(true);
        when(workflowNodeRecoveryService.isEnabled()).thenReturn(false);

        executor = new WorkflowExecutor(
                loader, registry, executionPlanStore, labelService,
                retryPolicyResolver, nodeRetryExecutor, upstreamOutputResolver,
                planExecutionAuditService, displayNameEnricher, planRunFinalizer,
                groundingChecker, groundingProperties, workflowNodeRecoveryService,
                workflowPauseService, generationRegistry);
    }

    @Test
    void runsLinearWorkflowAsPlanDag() {
        WorkflowDefinition def = WorkflowDefinition.from("knowledge-qa", List.of(
                new NodeSpec("start", "start", Map.of()),
                new NodeSpec("rag", "rag", Map.of("topK", "3")),
                new NodeSpec("answer", "answer", Map.of("prompt", "test"), "生成回答")
        ), List.of("start", "rag", "answer"));

        when(loader.load("knowledge-qa")).thenReturn(java.util.Optional.of(def));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "m1", "请假制度是什么", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "查制度"));

        List<StreamToken> tokens = executor.execute(ctx).collectList().block();
        assertThat(tokens).isNotNull().isNotEmpty();
        assertThat(tokens.stream().anyMatch(StreamToken::isStep)).isTrue();
        assertThat(tokens.stream().anyMatch(StreamToken::isContent)).isTrue();
        assertThat(tokens.stream().filter(StreamToken::isStep).map(t -> t.step().detail()))
                .anyMatch(detail -> detail != null && detail.contains("planId=static-plan-1"));
        verify(executionPlanStore).createDraft(eq(ctx), any(PlanJson.class));
        verify(executionPlanStore).markValidated(eq("static-plan-1"), any(PlanJson.class));
        verify(executionPlanStore).markRunning("static-plan-1");
        verify(planRunFinalizer).postWorkflow(eq(ctx), eq("static-plan-1"), any());
    }

    @Test
    void returnsErrorWhenDefinitionMissing() {
        when(loader.load("unknown")).thenReturn(java.util.Optional.empty());

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "m1", "test", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "unknown", Map.of(), "test"));

        List<StreamToken> tokens = executor.execute(ctx).collectList().block();
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).text()).contains("unknown");
        assertThat(tokens.get(0).text()).contains("未定义");
    }
}
