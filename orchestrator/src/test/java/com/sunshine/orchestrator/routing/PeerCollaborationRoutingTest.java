package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.ExpertCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.RoutingRuleProperties;
import com.sunshine.orchestrator.expert.ExpertBindingParser;
import com.sunshine.orchestrator.routing.policy.ExpertBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.GoldenRuleRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.LlmClassifierRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.PeerStructuralRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.StructuralRoutingPolicy;
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
import java.util.Map;

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
        RoutingRuleProperties routingProps = routingFixture();
        StructuralPlanMatcher structuralMatcher = new StructuralPlanMatcher(routingProps);
        PeerPatternMatcher peerMatcher = new PeerPatternMatcher(routingProps);
        RuleBasedRouter ruleRouter = new RuleBasedRouter(routingProps);
        WorkflowBindingParser workflowBindingParser = new WorkflowBindingParser(workflowCatalog);
        ExpertBindingParser expertBindingParser = new ExpertBindingParser(expertCatalogService);
        var chain = new RoutingPolicyChain(List.of(
                new WorkflowBindingRoutingPolicy(workflowBindingParser),
                new ExpertBindingRoutingPolicy(expertBindingParser),
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
    void e1_crossReviewRoutesToPeerCollab() {
        String query = "请制度专家和财务专家分别审查这笔报销是否合规，并互相验证";
        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PEER_COLLAB);
        assertThat(plan.params()).isEmpty();
        verify(intentRouter, never()).classifyPlan(anyString());
    }

    @Test
    void e2_pipelineStillRoutesToPlanWorkflow() {
        String query = "先检索报销制度，再查待审批列表，并对结果做合规分析";
        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.reason()).isEqualTo("structural:multi-step-plan");
    }

    private static RoutingRuleProperties routingFixture() {
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
                "finance", List.of("待审批", "待办"),
                "analysis", List.of("分析", "润色", "合规")));
        props.setStructural(structural);
        RoutingRuleProperties.Peer peer = new RoutingRuleProperties.Peer();
        peer.setEnabled(true);
        peer.setDefaultTemplateId("compliance-cross-review");
        peer.setStructuralPatterns(List.of(
                "互相验证",
                "交叉审查",
                "多专家讨论",
                "分别分析并质疑"));
        props.setPeer(peer);
        return props;
    }
}
