package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.AgentBindingParser;
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
    private WorkflowCatalog workflowCatalog;

    private ExecutionPlanRouter router;

    @BeforeEach
    void setUp() {
        PromptCatalogHolder catalogHolder = RoutingCatalogFixtures.seedHolder();
        SkillBindingRoutingPolicy skillPolicy = new SkillBindingRoutingPolicy(skillBindingParser, catalogHolder);
        WorkflowBindingRoutingPolicy workflowPolicy =
                new WorkflowBindingRoutingPolicy(new WorkflowBindingParser(workflowCatalog));
        AgentBindingRoutingPolicy agentPolicy = new AgentBindingRoutingPolicy(agentBindingParser);
        when(agentBindingParser.parse(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv ->
                com.sunshine.orchestrator.catalog.AgentBindingOutcome.none(inv.getArgument(0)));
        when(agentBindingParser.stripAgentMentions(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        router = new ExecutionPlanRouter(
                new SkillDiscoveryService(skillCatalogService),
                new ForcedExecutionRouter(
                        skillPolicy, agentPolicy, catalogHolder, intentRouter, workflowPolicy,
                        new WorkflowBindingParser(workflowCatalog)),
                skillBindingParser,
                agentBindingParser);
        when(skillBindingParser.stripAtMention(org.mockito.ArgumentMatchers.anyString())
                ).thenAnswer(inv -> inv.getArgument(0));
        when(skillCatalogService.indexEntries()).thenReturn(List.of());
        when(skillCatalogService.sanitizeSkillPlan(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void multiStepQuery_withPro_hitsStructuralRule() {
        String query = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论";
        when(skillBindingParser.parse(query)).thenReturn(SkillBindingOutcome.none(query));

        ExecutionPlan plan = router.route(ctx(query, ExecutionPreference.PRO)).block();

        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.STRUCTURAL_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void atSkillBindingSingleStep_withFast_keepsSkillAndMayCallL3() {
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "是否合规", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse("@finance-analysis 是否合规")).thenReturn(binding);
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                new ExecutionPlan(ExecutionMode.FAST, null,
                        Map.of("reactPromptId", "react-prompt.from-llm"), "llm")));

        ExecutionPlan plan = router.route(ctx("@finance-analysis 是否合规", ExecutionPreference.FAST)).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.params()).containsEntry("reactPromptId", "react-prompt.from-llm");
    }

    @Test
    void atSkillMultiStep_withPro_keepsSkillDrivenParams() {
        String query = "@finance-analysis 先查制度再拉待办再分析再润色";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "先查制度再拉待办再分析再润色", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse(query)).thenReturn(binding);

        ExecutionPlan plan = router.route(ctx(query, ExecutionPreference.PRO)).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_PLANNER_MODE))
                .isEqualTo(SkillBindingOutcome.PLANNER_MODE_SKILL_DRIVEN);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
    }

    @Test
    void ruleHit_withWorkflow_bindsFinanceList() {
        String query = "有哪些待审批报销";
        when(skillBindingParser.parse(query)).thenReturn(SkillBindingOutcome.none(query));

        ExecutionPlan plan = router.route(ctx(query, ExecutionPreference.WORKFLOW)).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-list");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_LIST_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void defaultRoute_pinsFast_withoutAutoJudge() {
        String query = "青松假有多少天、怎么申请";
        when(skillBindingParser.parse(query)).thenReturn(SkillBindingOutcome.none(query));
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa",
                        Map.of("reactPromptId", "react-prompt.x"), "llm")));

        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-react");
        assertThat(plan.params()).containsEntry("reactPromptId", "react-prompt.x");
    }

    private static RoutingContext ctx(String message, ExecutionPreference preference) {
        return new RoutingContext(message, null, preference, null, null);
    }
}
