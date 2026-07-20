package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.ExpertCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.expert.ExpertBindingParser;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.ExpertBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.LlmClassifierRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.UnifiedRuleRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** routing-golden-set §E 路由边界 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PeerCollaborationRoutingTest {

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
    void e1_crossReviewRoutesToPeerCollab() {
        String query = "请制度专家和财务专家分别审查这笔报销是否合规，并互相验证";
        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PEER_COLLAB);
        assertThat(plan.params()).isEmpty();
        assertThat(plan.reason()).isEqualTo("rule:" + RoutingCatalogFixtures.PEER_ID);
        verify(intentRouter, never()).classifyPlan(anyString());
    }

    @Test
    void e2_pipelineStillRoutesToPlanWorkflow() {
        String query = "先检索报销制度，再查待审批列表，并对结果做合规分析";
        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.reason()).isEqualTo("rule:" + RoutingCatalogFixtures.STRUCTURAL_ID);
    }
}
