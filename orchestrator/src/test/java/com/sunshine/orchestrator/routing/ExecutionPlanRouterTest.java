package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import com.sunshine.orchestrator.routing.policy.LlmClassifierRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.UnifiedRuleRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.skill.SkillBindingSource;
import com.sunshine.orchestrator.skill.SkillDiscoveryService;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
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
    private IntentRouter intentRouter;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private WorkflowCatalog workflowCatalog;

    private ExecutionPlanRouter router;

    @BeforeEach
    void setUp() {
        PromptCatalogHolder catalogHolder = RoutingCatalogFixtures.seedHolder();
        SkillBindingRoutingPolicy skillPolicy = new SkillBindingRoutingPolicy(skillBindingParser, catalogHolder);
        WorkflowBindingParser workflowBindingParser = new WorkflowBindingParser(workflowCatalog);
        var chain = new RoutingPolicyChain(List.of(
                new WorkflowBindingRoutingPolicy(workflowBindingParser),
                skillPolicy,
                new UnifiedRuleRoutingPolicy(catalogHolder),
                new LlmClassifierRoutingPolicy(intentRouter, queryRewriteService)));
        router = new ExecutionPlanRouter(chain, new SkillDiscoveryService(skillCatalogService),
                new ForcedExecutionRouter(skillPolicy, catalogHolder, intentRouter),
                skillBindingParser);
        when(skillBindingParser.stripAtMention(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(skillCatalogService.indexEntries()).thenReturn(List.of());
        when(skillCatalogService.sanitizeSkillPlan(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void multiStepQueryRoutesToPlanWorkflowBeforeRules() {
        String query = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论";
        when(skillBindingParser.parse(query)).thenReturn(SkillBindingOutcome.none(query));

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.reason()).isEqualTo("rule:" + RoutingCatalogFixtures.STRUCTURAL_ID);
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
        String query = "有哪些待审批报销";
        when(skillBindingParser.parse(query)).thenReturn(SkillBindingOutcome.none(query));

        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-list");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_LIST_ID);
        verify(queryRewriteService, never()).rewriteForIntent(org.mockito.ArgumentMatchers.anyString());
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shortQueryUsesIntentRewriteBeforeClassify() {
        when(skillBindingParser.parse("待审批"))
                .thenReturn(SkillBindingOutcome.none("待审批"));
        when(queryRewriteService.shouldRewriteIntent("待审批")).thenReturn(true);
        when(queryRewriteService.rewriteForIntent("待审批", null, null))
                .thenReturn(QueryRewriteOutcome.of("intent", "待审批", "查询待审批报销消息", 0));
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of(), "llm");
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(plan));

        assertThat(router.route("待审批").block()).isEqualTo(plan);
        verify(intentRouter).classifyPlan(org.mockito.ArgumentMatchers.<RoutingContext>argThat(
                ctx -> "查询待审批报销消息".equals(ctx.userMessage())));
    }

    @Test
    void longQuerySkipsIntentRewrite() {
        String query = "青松假有多少天、怎么申请";
        when(skillBindingParser.parse(query))
                .thenReturn(SkillBindingOutcome.none(query));
        when(queryRewriteService.shouldRewriteIntent(query)).thenReturn(false);
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "llm");
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(plan));

        assertThat(router.route(query).block()).isEqualTo(plan);
        verify(queryRewriteService, never()).rewriteForIntent(org.mockito.ArgumentMatchers.anyString());
    }
}
