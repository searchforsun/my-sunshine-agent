package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanRouter;
import com.sunshine.orchestrator.routing.ExecutionPreference;
import com.sunshine.orchestrator.routing.ForcedExecutionRouter;
import com.sunshine.orchestrator.routing.RoutingCatalogFixtures;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.AgentBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.skill.SkillBindingParser;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** routing-golden-set §F — v6：fast 钉死 ReAct；pro 钉死结构规则；无 auto 互转 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReactTaskBoardRoutingTest {

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
        when(agentBindingParser.parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv ->
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
        when(skillBindingParser.parse(anyString(), any(), anyString())).thenAnswer(inv -> SkillBindingOutcome.none(inv.getArgument(0)));
        when(skillCatalogService.indexEntries()).thenReturn(List.of());
        when(skillCatalogService.sanitizeSkillPlan(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void f1_exploratoryPrompt_withFast_staysReact() {
        String query = "帮我查待审批报销，并对有风险的单据逐条说明原因";
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "llm")));

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        verify(intentRouter).classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class));
    }

    @Test
    void f2_summaryPrompt_withFast_staysReact() {
        String query = "用财务工具汇总各状态数量，并解释异常偏多的状态";
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "llm")));

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
    }

    @Test
    void fn1_mainAcceptance_withPro_hitsStructuralNotReact() {
        String query = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论";

        ExecutionPlan plan = router.route(new RoutingContext(
                query, null, ExecutionPreference.PRO, null, null)).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.STRUCTURAL_ID);
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class));
    }
}
