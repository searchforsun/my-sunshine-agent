package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.AgentBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.UnifiedRuleRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.skill.SkillDiscoveryService;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import com.sunshine.routing.RoutingRuleDef;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 轨 A/B：同 query 不同 mode → 规则命中域不同；L3 含 planMode 时丢弃且 mode 不变。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrackRoutingTest {

    private static final String COMPLIANCE_QUERY = "待审批是否合规";
    private static final String POLICY_QUERY = "差旅办法制度怎么说";

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

    private PromptCatalogHolder catalogHolder;
    private ExecutionPlanRouter router;
    private List<RoutingRuleDef> rules;

    @BeforeEach
    void setUp() {
        catalogHolder = RoutingCatalogFixtures.seedHolder();
        rules = catalogHolder.snapshot().routingRules();
        SkillBindingRoutingPolicy skillPolicy = new SkillBindingRoutingPolicy(skillBindingParser);
        AgentBindingRoutingPolicy agentPolicy = new AgentBindingRoutingPolicy(agentBindingParser);
        WorkflowBindingRoutingPolicy workflowPolicy =
                new WorkflowBindingRoutingPolicy(new WorkflowBindingParser(workflowCatalog));
        router = new ExecutionPlanRouter(
                new SkillDiscoveryService(skillCatalogService),
                new ForcedExecutionRouter(
                        skillPolicy, agentPolicy, catalogHolder, intentRouter, workflowPolicy,
                        new WorkflowBindingParser(workflowCatalog), skillCatalogService,
                        agentCatalogService, workflowCatalog),
                skillBindingParser,
                agentBindingParser);

        when(skillBindingParser.parse(anyString(), any(), anyString())).thenAnswer(inv -> SkillBindingOutcome.none(inv.getArgument(0)));
        when(skillBindingParser.stripSlashMention(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(agentBindingParser.stripAgentMentions(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(agentBindingParser.parse(anyString(), anyString())).thenAnswer(inv ->
                com.sunshine.orchestrator.catalog.AgentBindingOutcome.none(inv.getArgument(0)));
        when(skillCatalogService.sanitizeSkillPlan(any())).thenAnswer(inv -> inv.getArgument(0));
        when(intentRouter.classifyPlan(any(RoutingContext.class)))
                .thenReturn(Mono.just(ExecutionPlan.reactFallback("llm")));
        when(workflowCatalog.isKnownWorkflow(anyString())).thenReturn(true);
    }

    @Test
    void sameComplianceQuery_trackASkipsWorkflowRule_trackBHitsFinanceSmart() {
        Optional<ExecutionPlan> trackA = UnifiedRuleRoutingPolicy.matchForLockedMode(
                rules, COMPLIANCE_QUERY, ExecutionMode.FAST);
        Optional<ExecutionPlan> trackB = UnifiedRuleRoutingPolicy.matchForLockedMode(
                rules, COMPLIANCE_QUERY, ExecutionMode.WORKFLOW);

        assertThat(trackA).isEmpty();
        assertThat(trackB).isPresent();
        assertThat(trackB.get().workflowId()).isEqualTo("finance-smart");
        assertThat(trackB.get().mode()).isEqualTo(ExecutionMode.WORKFLOW);

        ExecutionPlan fastPlan = router.route(ctx(ExecutionMode.FAST, COMPLIANCE_QUERY, null, "chat"))
                .block();
        ExecutionPlan workflowPlan = router.route(
                ctx(ExecutionMode.WORKFLOW, COMPLIANCE_QUERY, null, "chat")).block();

        assertThat(fastPlan).isNotNull();
        assertThat(fastPlan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(fastPlan.workflowId()).isNull();

        assertThat(workflowPlan).isNotNull();
        assertThat(workflowPlan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(workflowPlan.workflowId()).isEqualTo("finance-smart");
    }

    @Test
    void trackARule_sharedByFastAndPro_workflowSkips() {
        Optional<ExecutionPlan> trackFast = UnifiedRuleRoutingPolicy.matchForLockedMode(
                rules, POLICY_QUERY, ExecutionMode.FAST);
        Optional<ExecutionPlan> trackPro = UnifiedRuleRoutingPolicy.matchForLockedMode(
                rules, POLICY_QUERY, ExecutionMode.PRO);
        Optional<ExecutionPlan> trackWorkflow = UnifiedRuleRoutingPolicy.matchForLockedMode(
                rules, POLICY_QUERY, ExecutionMode.WORKFLOW);

        assertThat(trackFast).isPresent();
        assertThat(trackPro).isPresent();
        assertThat(trackPro.get().ruleId()).isEqualTo(RoutingCatalogFixtures.REACT_POLICY_QA_ID);
        assertThat(trackWorkflow).isEmpty();

        ExecutionPlan proPlan = router.route(ctx(ExecutionMode.PRO, POLICY_QUERY, null, "task"))
                .block();
        assertThat(proPlan).isNotNull();
        assertThat(proPlan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(proPlan.params()).containsEntry("skill", "policy-qa");
    }

    @Test
    void trackA_ignoresHashWorkflowMention() {
        when(intentRouter.classifyPlan(any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(
                        ExecutionMode.FAST, null, Map.of("status", "draft"), "llm")));

        ExecutionPlan plan = router.route(
                ctx(ExecutionMode.FAST, "#knowledge-qa 制度怎么说", null, "chat")).block();

        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.workflowId()).isNull();
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
    }

    @Test
    void l3PlanModeAndExecutionMode_discarded_lockedModeUnchanged() {
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenAnswer(inv -> {
            RoutingContext c = inv.getArgument(0);
            ExecutionPlan raw = new ExecutionPlanParser().parse("""
                    {"planMode":"harness","executionMode":"workflow","mode":"workflow",\
                    "workflowId":"finance-smart","skillIds":["finance-analysis"],\
                    "agentIds":["contract-review"],"reason":"llm-lied"}
                    """);
            return Mono.just(IntentRouter.applyLockedMode(raw, c.lockedMode()));
        });

        ExecutionPlan plan = router.route(ctx(ExecutionMode.FAST, "随便聊聊", null, "chat")).block();

        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.workflowId()).isNull();
        assertThat(plan.params()).containsEntry(SkillBindingOutcome.PARAM_SKILL, "finance-analysis");
        assertThat(plan.params()).containsEntry("agentIds", "contract-review");
    }

    @Test
    void routingContext_preservesKind_defaultChat() {
        RoutingContext withKind = ctx(ExecutionMode.PRO, "hi", null, "task");
        assertThat(withKind.kindOrDefault()).isEqualTo("task");
        assertThat(withKind.withLockedMode(ExecutionMode.PRO).kindOrDefault()).isEqualTo("task");

        RoutingContext bare = new RoutingContext("hi", null);
        assertThat(bare.kindOrDefault()).isEqualTo("chat");
    }

    private static RoutingContext ctx(
            ExecutionMode preference, String query, String workflowId, String kind) {
        return new RoutingContext(query, null, preference, workflowId, null, null, null, kind);
    }
}
