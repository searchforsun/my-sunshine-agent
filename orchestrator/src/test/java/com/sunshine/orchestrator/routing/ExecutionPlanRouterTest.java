package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.RoutingRuleProperties;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import com.sunshine.orchestrator.routing.policy.GoldenRuleRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.LlmClassifierRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.StructuralRoutingPolicy;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.skill.SkillBindingSource;
import com.sunshine.orchestrator.skill.SkillDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionPlanRouterTest {

    @Mock
    private SkillBindingParser skillBindingParser;
    @Mock
    private RuleBasedRouter ruleBasedRouter;
    @Mock
    private IntentRouter intentRouter;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private SkillCatalogService skillCatalogService;

    private ExecutionPlanRouter router;

    @BeforeEach
    void setUp() {
        RoutingRuleProperties routingProps = structuralFixture();
        StructuralPlanMatcher structuralMatcher = new StructuralPlanMatcher(routingProps);
        var chain = new RoutingPolicyChain(List.of(
                new SkillBindingRoutingPolicy(skillBindingParser, structuralMatcher),
                new StructuralRoutingPolicy(structuralMatcher),
                new GoldenRuleRoutingPolicy(ruleBasedRouter, structuralMatcher),
                new LlmClassifierRoutingPolicy(intentRouter, queryRewriteService)));
        router = new ExecutionPlanRouter(chain, new SkillDiscoveryService(skillCatalogService),
                new ForcedExecutionRouter(
                        new SkillBindingRoutingPolicy(skillBindingParser, structuralMatcher),
                        ruleBasedRouter, intentRouter, skillBindingParser));
        when(skillCatalogService.indexEntries()).thenReturn(List.of());
    }

    @Test
    void multiStepQueryRoutesToPlanWorkflowBeforeRules() {
        String query = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论";
        when(skillBindingParser.parse(query)).thenReturn(SkillBindingOutcome.none(query));

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        verify(ruleBasedRouter, never()).match(query);
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void atSkillBindingSingleStepRoutesToReact() {
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "是否合规", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse("@finance-analysis 是否合规")).thenReturn(binding);

        ExecutionPlan plan = router.route("@finance-analysis 是否合规").block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        verify(ruleBasedRouter, never()).match(org.mockito.ArgumentMatchers.anyString());
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void atSkillMultiStepRoutesToPlanWorkflow5B() {
        String query = "@finance-analysis 先查制度再拉待办再分析再润色";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "先查制度再拉待办再分析再润色", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse(query)).thenReturn(binding);

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_PLANNER_MODE))
                .isEqualTo(SkillBindingOutcome.PLANNER_MODE_SKILL_DRIVEN);
        assertThat(plan.reason()).contains("5b-skill-plan");
    }

    @Test
    void ruleHitSkipsIntentRewrite() {
        when(skillBindingParser.parse("年假几天"))
                .thenReturn(SkillBindingOutcome.none("年假几天"));
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "rule");
        when(ruleBasedRouter.match("年假几天")).thenReturn(java.util.Optional.of(plan));

        assertThat(router.route("年假几天").block()).isEqualTo(plan);
        verify(queryRewriteService, never()).rewriteForIntent(org.mockito.ArgumentMatchers.anyString());
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shortQueryUsesIntentRewriteBeforeClassify() {
        when(skillBindingParser.parse("待审批"))
                .thenReturn(SkillBindingOutcome.none("待审批"));
        when(ruleBasedRouter.match("待审批")).thenReturn(java.util.Optional.empty());
        when(queryRewriteService.shouldRewriteIntent("待审批")).thenReturn(true);
        when(queryRewriteService.rewriteForIntent("待审批", null))
                .thenReturn(QueryRewriteOutcome.of("intent", "待审批", "查询待审批报销消息", 0));
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of(), "llm");
        when(intentRouter.classifyPlan("查询待审批报销消息")).thenReturn(Mono.just(plan));

        assertThat(router.route("待审批").block()).isEqualTo(plan);
        verify(intentRouter).classifyPlan("查询待审批报销消息");
    }

    @Test
    void longQuerySkipsIntentRewrite() {
        String query = "年假可以请几天";
        when(skillBindingParser.parse(query))
                .thenReturn(SkillBindingOutcome.none(query));
        when(ruleBasedRouter.match(query)).thenReturn(java.util.Optional.empty());
        when(queryRewriteService.shouldRewriteIntent(query)).thenReturn(false);
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "llm");
        when(intentRouter.classifyPlan(query)).thenReturn(Mono.just(plan));

        assertThat(router.route(query).block()).isEqualTo(plan);
        verify(queryRewriteService, never()).rewriteForIntent(org.mockito.ArgumentMatchers.anyString());
    }

    private static RoutingRuleProperties structuralFixture() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        RoutingRuleProperties.Structural structural = new RoutingRuleProperties.Structural();
        structural.setEnabled(true);
        structural.setMinDomainGroups(2);
        structural.setMultiStepPatterns(List.of(
                "先.+再",
                "再.+(并|然后|接着)",
                "并对.+?(分析|审查|检查|评估)"));
        structural.setDomainGroups(Map.of(
                "knowledge", List.of("制度", "检索"),
                "finance", List.of("待审批", "待办", "拉"),
                "analysis", List.of("分析", "润色", "合规")));
        props.setStructural(structural);
        return props;
    }
}
