package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.AgentBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 4：三模式均钉死；默认 preference=FAST 走 ForcedExecutionRouter。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionPlanRouterTest {

    @Mock
    private SkillBindingParser skillBindingParser;
    @Mock
    private AgentBindingParser agentBindingParser;
    @Mock
    private IntentRouter intentRouter;
    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private AgentCatalogService agentCatalogService;
    @Mock
    private WorkflowCatalog workflowCatalog;

    private ExecutionPlanRouter router;

    @BeforeEach
    void setUp() {
        PromptCatalogHolder catalogHolder = RoutingCatalogFixtures.seedHolder();
        SkillBindingRoutingPolicy skillPolicy = new SkillBindingRoutingPolicy(skillBindingParser);
        WorkflowBindingRoutingPolicy workflowPolicy =
                new WorkflowBindingRoutingPolicy(new WorkflowBindingParser(workflowCatalog));
        AgentBindingRoutingPolicy agentPolicy = new AgentBindingRoutingPolicy(agentBindingParser);
        when(agentBindingParser.parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv ->
                com.sunshine.orchestrator.catalog.AgentBindingOutcome.none(inv.getArgument(0)));
        when(agentBindingParser.stripAgentMentions(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        router = new ExecutionPlanRouter(
                new SkillDiscoveryService(skillCatalogService),
                new ForcedExecutionRouter(
                        skillPolicy, agentPolicy, catalogHolder, intentRouter, workflowPolicy,
                        new WorkflowBindingParser(workflowCatalog), skillCatalogService,
                        agentCatalogService, workflowCatalog),
                skillBindingParser,
                agentBindingParser);
        when(skillBindingParser.stripSlashMention(org.mockito.ArgumentMatchers.anyString())
                ).thenAnswer(inv -> inv.getArgument(0));
        when(skillCatalogService.indexEntries()).thenReturn(List.of());
        when(skillCatalogService.sanitizeSkillPlan(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void policyQuery_withPro_hitsSharedTrackARule() {
        String query = "差旅办法制度怎么说";
        when(skillBindingParser.parse(eq(query), any(), any())).thenReturn(SkillBindingOutcome.none(query));

        ExecutionPlan plan = router.route(ctx(query, ExecutionMode.PRO)).block();

        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-pro");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.REACT_POLICY_QA_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void slashSkillBindingSingleStep_withFast_skipsL3WhenSkillBound() {
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "是否合规", SkillBindingSource.SLASH_MENTION);
        when(skillBindingParser.parse(eq("/finance-analysis 是否合规"), any(), any())).thenReturn(binding);
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                new ExecutionPlan(ExecutionMode.FAST, null,
                        Map.of("status", "pending"), "llm")));

        ExecutionPlan plan = router.route(ctx("/finance-analysis 是否合规", ExecutionMode.FAST)).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.params()).doesNotContainKey("status");
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void slashSkillMultiStep_withPro_bindsSkillKeepsMode() {
        String query = "/finance-analysis 先查制度再拉待办再分析再润色";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "先查制度再拉待办再分析再润色", SkillBindingSource.SLASH_MENTION);
        when(skillBindingParser.parse(eq(query), any(), any())).thenReturn(binding);

        ExecutionPlan plan = router.route(ctx(query, ExecutionMode.PRO)).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.params()).doesNotContainKey(SkillBindingOutcome.PARAM_PLANNER_MODE);
        assertThat(plan.reason()).isEqualTo("user:forced-pro");
    }

    @Test
    void ruleHit_withWorkflow_bindsFinanceList() {
        String query = "有哪些待审批报销";
        when(skillBindingParser.parse(eq(query), any(), any())).thenReturn(SkillBindingOutcome.none(query));

        ExecutionPlan plan = router.route(ctx(query, ExecutionMode.WORKFLOW)).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-list");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_LIST_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void defaultRoute_pinsFast_withoutAutoJudge() {
        String query = "青松假有多少天、怎么申请";
        when(skillBindingParser.parse(eq(query), any(), any())).thenReturn(SkillBindingOutcome.none(query));
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa",
                        Map.of("status", "draft"), "llm")));

        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
        assertThat(plan.params()).containsEntry("status", "draft");
    }

    private static RoutingContext ctx(String message, ExecutionMode preference) {
        return new RoutingContext(message, null, preference, null, null);
    }
}
