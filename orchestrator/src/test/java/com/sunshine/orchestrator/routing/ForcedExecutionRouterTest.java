package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForcedExecutionRouterTest {

    @Mock
    private SkillBindingRoutingPolicy skillBindingRoutingPolicy;
    @Mock
    private IntentRouter intentRouter;
    @Mock
    private WorkflowCatalog workflowCatalog;

    private PromptCatalogHolder catalogHolder;
    private ForcedExecutionRouter router;

    @BeforeEach
    void setUp() {
        catalogHolder = RoutingCatalogFixtures.seedHolder();
        WorkflowBindingRoutingPolicy workflowPolicy =
                new WorkflowBindingRoutingPolicy(new WorkflowBindingParser(workflowCatalog));
        router = new ForcedExecutionRouter(skillBindingRoutingPolicy, catalogHolder, intentRouter, workflowPolicy);
        when(workflowCatalog.isKnownWorkflow(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
    }

    @Test
    void resolve_react_withSkillBinding_stillCallsL3ForReactPrompt() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.FAST, null, Map.of("skill", "finance-analysis"), "skill:@mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(new ExecutionPlan(
                ExecutionMode.WORKFLOW, "finance-smart",
                Map.of("reactPromptId", "react-prompt.from-llm"), "llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("@finance-analysis 分析", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-react");
        assertThat(plan.params()).containsEntry("skill", "finance-analysis");
        assertThat(plan.params()).containsEntry("reactPromptId", "react-prompt.from-llm");
        ArgumentCaptor<RoutingContext> cap = ArgumentCaptor.forClass(RoutingContext.class);
        verify(intentRouter).classifyPlan(cap.capture());
        assertThat(cap.getValue().lockedMode()).isEqualTo(ExecutionMode.FAST);
    }

    @Test
    void resolve_react_policyPhrase_bindsReactPromptFromRule() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("差旅办法制度怎么说", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-react");
        assertThat(plan.params()).containsEntry("reactPromptId", "react-prompt.policy-qa");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.REACT_POLICY_QA_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_react_withSkillAndPolicyRule_mergesBoth() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.FAST, null, Map.of("skill", "policy-review"), "skill:@mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("@policy-review 差旅办法制度怎么说", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.params()).containsEntry("skill", "policy-review");
        assertThat(plan.params()).containsEntry("reactPromptId", "react-prompt.policy-qa");
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_react_ignoresWorkflowRule_keepsForcedMode() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                ExecutionPlan.reactFallback("llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("待审批是否合规", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.workflowId()).isNull();
        assertThat(plan.reason()).isEqualTo("user:forced-react");
        assertThat(plan.ruleId()).isNull();
    }

    @Test
    void resolve_react_structuralPhrase_doesNotPromoteToPlan() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                ExecutionPlan.reactFallback("llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("先查制度再查待审批并对合规分析", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-react");
    }

    @Test
    void resolve_planWorkflow_withAtSkill_keepsForcedMode() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of("skill", "policy-review", "effectiveQuery", "老家有事请事假是否合理"),
                "skill:@mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("@policy-review 老家有事请事假是否合理", null, ExecutionPreference.PRO, null, null),
                ExecutionPreference.PRO, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
        assertThat(plan.params()).containsEntry("skill", "policy-review");
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_planWorkflow_structural_hitsSameModeRule() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("先查制度再查待审批并对合规分析", null, ExecutionPreference.PRO, null, null),
                ExecutionPreference.PRO, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.STRUCTURAL_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_workflow_withExplicitId() {
        ExecutionPlan plan = router.resolve(
                new RoutingContext("年假", null, ExecutionPreference.WORKFLOW, "knowledge-qa", null),
                ExecutionPreference.WORKFLOW, "knowledge-qa").block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("workflow:client");
        assertThat(plan.params()).containsEntry("effectiveQuery", "年假");
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_workflow_fromCatalogRule() {
        ExecutionPlan plan = router.resolve(
                new RoutingContext("有哪些待审批报销", null, ExecutionPreference.WORKFLOW, null, null),
                ExecutionPreference.WORKFLOW, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-list");
        assertThat(plan.reason()).isEqualTo("user:forced-workflow");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_LIST_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_workflow_fromIntentClassifier() {
        when(intentRouter.classifyPlan(any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(
                        ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("年假制度", null, ExecutionPreference.WORKFLOW, null, null),
                ExecutionPreference.WORKFLOW, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        ArgumentCaptor<RoutingContext> cap = ArgumentCaptor.forClass(RoutingContext.class);
        verify(intentRouter).classifyPlan(cap.capture());
        assertThat(cap.getValue().lockedMode()).isEqualTo(ExecutionMode.WORKFLOW);
    }
}
