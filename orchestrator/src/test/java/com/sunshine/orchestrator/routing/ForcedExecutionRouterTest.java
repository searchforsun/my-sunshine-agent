package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForcedExecutionRouterTest {

    @Mock
    private SkillBindingRoutingPolicy skillBindingRoutingPolicy;
    @Mock
    private IntentRouter intentRouter;

    private PromptCatalogHolder catalogHolder;
    private ForcedExecutionRouter router;

    @BeforeEach
    void setUp() {
        catalogHolder = RoutingCatalogFixtures.seedHolder();
        router = new ForcedExecutionRouter(skillBindingRoutingPolicy, catalogHolder, intentRouter);
    }

    @Test
    void resolve_react_withSkillBinding() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.REACT, null, Map.of("skill", "finance-analysis"), "skill:@mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("@finance-analysis 分析", null, ExecutionPreference.REACT, null, null),
                ExecutionPreference.REACT, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        assertThat(plan.reason()).isEqualTo("user:forced-react");
        assertThat(plan.params()).containsEntry("skill", "finance-analysis");
    }

    @Test
    void resolve_planWorkflow_withAtSkill_keepsForcedMode() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.REACT, null,
                Map.of("skill", "policy-review", "effectiveQuery", "老家有事请事假是否合理"),
                "skill:@mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("@policy-review 老家有事请事假是否合理", null, ExecutionPreference.PLAN_WORKFLOW, null, null),
                ExecutionPreference.PLAN_WORKFLOW, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
        assertThat(plan.params()).containsEntry("skill", "policy-review");
    }

    @Test
    void resolve_workflow_withExplicitId() {
        ExecutionPlan plan = router.resolve(
                new RoutingContext("年假", null, ExecutionPreference.WORKFLOW, "knowledge-qa", null),
                ExecutionPreference.WORKFLOW, "knowledge-qa").block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
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
    }

    @Test
    void resolve_workflow_fromIntentClassifier() {
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(
                ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("年假制度", null, ExecutionPreference.WORKFLOW, null, null),
                ExecutionPreference.WORKFLOW, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
    }

    @Test
    void resolve_peerCollab_withoutTemplate() {
        ExecutionPlan plan = router.resolve(
                new RoutingContext("待审批是否合规", null, ExecutionPreference.PEER_COLLAB, null, null),
                ExecutionPreference.PEER_COLLAB, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PEER_COLLAB);
        assertThat(plan.reason()).isEqualTo("user:forced-peer-collab");
        assertThat(plan.params()).isEmpty();
    }
}
