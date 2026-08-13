package com.sunshine.orchestrator.routing;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Routing v6：用户 executionMode 钉死；L3 不得改 mode；workflow 无候选显式失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionPlanRouterV6Test {

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
        when(skillBindingParser.parse(any(), any(), any())).thenAnswer(inv -> SkillBindingOutcome.none(inv.getArgument(0)));
        when(skillBindingParser.stripAtMention(any())).thenAnswer(inv -> inv.getArgument(0));
        when(skillCatalogService.sanitizeSkillPlan(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void userPro_neverBecomesFast_evenIfL3SaysReact() {
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                new ExecutionPlan(ExecutionMode.FAST, null,
                        Map.of("reactPromptId", "react-prompt.from-llm"), "llm:says-react")));

        ExecutionPlan plan = router.route(new RoutingContext(
                "随便聊聊", null, ExecutionPreference.PRO, null, null)).block();

        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
        assertThat(plan.params()).containsEntry("reactPromptId", "react-prompt.from-llm");
    }

    @Test
    void userWorkflow_withoutCandidate_errors_notFast() {
        when(intentRouter.classifyPlan(any(RoutingContext.class)))
                .thenReturn(Mono.just(ExecutionPlan.reactFallback("llm:no-workflow")));

        assertThatThrownBy(() -> router.route(new RoutingContext(
                "完全无关的闲聊", null, ExecutionPreference.WORKFLOW, null, null)).block())
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.WORKFLOW_TEMPLATE_NOT_FOUND);
    }
}
