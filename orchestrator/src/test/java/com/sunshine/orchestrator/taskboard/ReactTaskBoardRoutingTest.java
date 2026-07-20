package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.ExpertCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.expert.ExpertBindingParser;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.ExecutionPlanRouter;
import com.sunshine.orchestrator.routing.ForcedExecutionRouter;
import com.sunshine.orchestrator.routing.RoutingCatalogFixtures;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import com.sunshine.orchestrator.routing.policy.ExpertBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.LlmClassifierRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.UnifiedRuleRoutingPolicy;
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
    @Mock
    private WorkflowCatalog workflowCatalog;
    @Mock
    private ExpertCatalogService expertCatalogService;

    private ExecutionPlanRouter router;

    @BeforeEach
    void setUp() {
        PromptCatalogHolder catalogHolder = RoutingCatalogFixtures.seedHolder();
        SkillBindingRoutingPolicy skillPolicy = new SkillBindingRoutingPolicy(skillBindingParser, catalogHolder);
        WorkflowBindingParser workflowBindingParser = new WorkflowBindingParser(workflowCatalog);
        ExpertBindingParser expertBindingParser = new ExpertBindingParser(expertCatalogService);
        var chain = new RoutingPolicyChain(List.of(
                new WorkflowBindingRoutingPolicy(workflowBindingParser),
                new ExpertBindingRoutingPolicy(expertBindingParser),
                skillPolicy,
                new UnifiedRuleRoutingPolicy(catalogHolder),
                new LlmClassifierRoutingPolicy(intentRouter, queryRewriteService)));
        router = new ExecutionPlanRouter(chain, new SkillDiscoveryService(skillCatalogService),
                new ForcedExecutionRouter(skillPolicy, catalogHolder, intentRouter),
                skillBindingParser);
        when(skillBindingParser.parse(anyString())).thenAnswer(inv -> SkillBindingOutcome.none(inv.getArgument(0)));
        when(skillCatalogService.indexEntries()).thenReturn(List.of());
        when(skillCatalogService.sanitizeSkillPlan(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void f1_exploratoryPromptRoutesToReact() {
        String query = "帮我查待审批报销，并对有风险的单据逐条说明原因";
        when(queryRewriteService.shouldRewriteIntent(query)).thenReturn(false);
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(com.sunshine.orchestrator.routing.policy.RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "llm")));

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        verify(intentRouter).classifyPlan(org.mockito.ArgumentMatchers.any(com.sunshine.orchestrator.routing.policy.RoutingContext.class));
    }

    @Test
    void f2_summaryPromptRoutesToReact() {
        String query = "用财务工具汇总各状态数量，并解释异常偏多的状态";
        when(queryRewriteService.shouldRewriteIntent(query)).thenReturn(false);
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(com.sunshine.orchestrator.routing.policy.RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "llm")));

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
    }

    @Test
    void fn1_mainAcceptanceRoutesToPlanWorkflowNotReact() {
        String query = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论";

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.reason()).isEqualTo("rule:" + RoutingCatalogFixtures.STRUCTURAL_ID);
        verify(intentRouter, never()).classifyPlan(anyString());
    }
}
