package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanRouter;
import com.sunshine.orchestrator.routing.ForcedExecutionRouter;
import com.sunshine.orchestrator.routing.RuleBasedRouter;
import com.sunshine.orchestrator.config.RoutingRuleProperties;
import com.sunshine.orchestrator.routing.StructuralPlanMatcher;
import com.sunshine.orchestrator.routing.policy.GoldenRuleRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.LlmClassifierRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.PeerStructuralRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.StructuralRoutingPolicy;
import com.sunshine.orchestrator.routing.PeerPatternMatcher;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.skill.SkillBindingParser;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** routing-golden-set §F 路由边界 — TaskBoard 与 plan-workflow 互斥 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReactTaskBoardRoutingTest {

    @Mock
    private SkillBindingParser skillBindingParser;
    @Mock
    private IntentRouter intentRouter;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private SkillCatalogService skillCatalogService;

    private ExecutionPlanRouter router;

    @BeforeEach
    void setUp() {
        RoutingRuleProperties routingProps = goldenRules();
        StructuralPlanMatcher structuralMatcher = new StructuralPlanMatcher(routingProps);
        PeerPatternMatcher peerMatcher = new PeerPatternMatcher(routingProps);
        RuleBasedRouter ruleRouter = new RuleBasedRouter(routingProps);
        var chain = new RoutingPolicyChain(List.of(
                new SkillBindingRoutingPolicy(skillBindingParser, structuralMatcher),
                new StructuralRoutingPolicy(structuralMatcher),
                new PeerStructuralRoutingPolicy(peerMatcher, structuralMatcher),
                new GoldenRuleRoutingPolicy(ruleRouter, structuralMatcher, peerMatcher),
                new LlmClassifierRoutingPolicy(intentRouter, queryRewriteService)));
        router = new ExecutionPlanRouter(chain, new SkillDiscoveryService(skillCatalogService),
                new ForcedExecutionRouter(
                        new SkillBindingRoutingPolicy(skillBindingParser, structuralMatcher),
                        ruleRouter, intentRouter),
                skillBindingParser);
        when(skillBindingParser.parse(anyString())).thenAnswer(inv -> SkillBindingOutcome.none(inv.getArgument(0)));
        when(skillCatalogService.indexEntries()).thenReturn(List.of());
    }

    @Test
    void f1_exploratoryPromptRoutesToReact() {
        String query = "帮我查待审批报销，并对有风险的单据逐条说明原因";
        when(queryRewriteService.shouldRewriteIntent(query)).thenReturn(false);
        when(intentRouter.classifyPlan(query)).thenReturn(Mono.just(
                new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "llm")));

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        verify(intentRouter).classifyPlan(query);
    }

    @Test
    void f2_summaryPromptRoutesToReact() {
        String query = "用财务工具汇总各状态数量，并解释异常偏多的状态";
        when(queryRewriteService.shouldRewriteIntent(query)).thenReturn(false);
        when(intentRouter.classifyPlan(query)).thenReturn(Mono.just(
                new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "llm")));

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
    }

    @Test
    void fn1_mainAcceptanceRoutesToPlanWorkflowNotReact() {
        String query = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论";

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.reason()).isEqualTo("structural:multi-step-plan");
        verify(intentRouter, never()).classifyPlan(anyString());
    }

    private static RoutingRuleProperties goldenRules() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        RoutingRuleProperties.Rule list = new RoutingRuleProperties.Rule();
        list.setId("rule-finance-list-pending");
        list.setPriority(10);
        list.setMatch("any");
        list.setPatterns(List.of("有哪些待审批", "查询待审批", "列出待审批"));
        RoutingRuleProperties.PlanSpec listPlan = new RoutingRuleProperties.PlanSpec();
        listPlan.setMode("workflow");
        listPlan.setWorkflowId("finance-list");
        list.setPlan(listPlan);
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
        props.setRules(List.of(list));
        return props;
    }
}
