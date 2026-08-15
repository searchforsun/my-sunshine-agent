package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.AgentCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.AgentBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForcedExecutionRouterTest {

    @Mock
    private SkillBindingRoutingPolicy skillBindingRoutingPolicy;
    @Mock
    private AgentBindingRoutingPolicy agentBindingRoutingPolicy;
    @Mock
    private IntentRouter intentRouter;
    @Mock
    private WorkflowCatalog workflowCatalog;
    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private AgentCatalogService agentCatalogService;

    private PromptCatalogHolder catalogHolder;
    private ForcedExecutionRouter router;

    @BeforeEach
    void setUp() {
        catalogHolder = RoutingCatalogFixtures.seedHolder();
        WorkflowBindingRoutingPolicy workflowPolicy =
                new WorkflowBindingRoutingPolicy(new WorkflowBindingParser(workflowCatalog));
        WorkflowBindingParser wbp = new WorkflowBindingParser(workflowCatalog);
        router = new ForcedExecutionRouter(
                skillBindingRoutingPolicy, agentBindingRoutingPolicy, catalogHolder, intentRouter,
                workflowPolicy, wbp, skillCatalogService, agentCatalogService, workflowCatalog);
        when(workflowCatalog.isKnownWorkflow(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(agentBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));
    }

    @Test
    void resolve_react_withSkillBinding_skipsL3AndDropsLlmtParams() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.FAST, null, Map.of("skill", "finance-analysis"), "skill:/mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(new ExecutionPlan(
                ExecutionMode.WORKFLOW, "finance-smart",
                Map.of("status", "pending"), "llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("/finance-analysis 分析", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
        assertThat(plan.params()).containsEntry("skill", "finance-analysis");
        assertThat(plan.params()).doesNotContainKey("status");
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_react_withAgentBinding_skipsRuleLayerAndL3() {
        ExecutionPlan agentPlan = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of("agentIds", "compliance-agent,policy-agent", "effectiveQuery", "分析一下"),
                "agent:$mention");
        when(agentBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(agentPlan)));
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));
        when(agentCatalogService.findIndex("compliance-agent"))
                .thenReturn(Optional.of(new AgentCatalogIndexEntry(
                        "compliance-agent", "业务合规对照智能体", "制度对照", true, "all", null,
                        "[\"sdk__sunshine-biz__list_my_expenses\"]")));
        when(agentCatalogService.findIndex("policy-agent"))
                .thenReturn(Optional.of(new AgentCatalogIndexEntry(
                        "policy-agent", "人事制度分析智能体", "制度解读", true, "all", null, null)));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("$compliance-agent $policy-agent 分析一下", null,
                        ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        // L0 命中即短路：多个 $agent 全部进可调度池，跳过规则层与 L3
        assertThat(plan.params()).containsEntry("agentIds", "compliance-agent,policy-agent");
        assertThat(plan.ruleId()).isNull();
        assertThat(plan.routingTraces()).extracting(RoutingTrace::layer)
                .contains("L0", "final")
                .doesNotContain("rule", "L3");
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_react_policyPhrase_bindsSkillFromRule() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("差旅办法制度怎么说", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
        assertThat(plan.params()).containsEntry("skill", "policy-qa");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.REACT_POLICY_QA_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_react_withSkillBinding_skipsRuleLayerAndL3() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.FAST, null, Map.of("skill", "policy-review"), "skill:/mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("/policy-review 差旅办法制度怎么说", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        // L0 命中即短路：规则层不再叠加，ruleId 为空
        assertThat(plan.params()).containsEntry("skill", "policy-review");
        assertThat(plan.ruleId()).isNull();
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_react_ignoresWorkflowRule_keepsForcedMode() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                ExecutionPlan.reactFallback("llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("待审批是否合规", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.workflowId()).isNull();
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
        assertThat(plan.ruleId()).isNull();
    }

    @Test
    void resolve_fast_noRuleHit_keepsForcedMode() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));
        when(intentRouter.classifyPlan(any(RoutingContext.class))).thenReturn(Mono.just(
                ExecutionPlan.reactFallback("llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("先查制度再查待审批并对合规分析", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
    }

    @Test
    void resolve_pro_withSlashSkill_keepsForcedMode() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.FAST, null,
                Map.of("skill", "policy-review", "effectiveQuery", "老家有事请事假是否合理"),
                "skill:/mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("/policy-review 老家有事请事假是否合理", null, ExecutionPreference.PRO, null, null),
                ExecutionPreference.PRO, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-pro");
        assertThat(plan.params()).containsEntry("skill", "policy-review");
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_pro_hitsSharedTrackARule() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("差旅办法制度怎么说", null, ExecutionPreference.PRO, null, null),
                ExecutionPreference.PRO, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-pro");
        assertThat(plan.params()).containsEntry("skill", "policy-qa");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.REACT_POLICY_QA_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_workflow_withExplicitId() {
        ExecutionPlan plan = router.resolve(
                new RoutingContext("年假", null, ExecutionPreference.WORKFLOW, "knowledge-qa", null),
                ExecutionPreference.WORKFLOW, "knowledge-qa").block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("workflow:client");
        assertThat(plan.params()).containsEntry("effectiveQuery", "年假");
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_workflow_fromCatalogRule() {
        ExecutionPlan plan = router.resolve(
                new RoutingContext("有哪些待审批报销", null, ExecutionPreference.WORKFLOW, null, null),
                ExecutionPreference.WORKFLOW, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-list");
        assertThat(plan.reason()).isEqualTo("user:forced-workflow");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_LIST_ID);
        verify(intentRouter, never()).classifyPlan(any(RoutingContext.class));
    }

    @Test
    void resolve_workflow_fromIntentClassifier() {
        when(intentRouter.classifyPlan(any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(
                        ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "llm")));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("年假制度", null, ExecutionPreference.WORKFLOW, null, null),
                ExecutionPreference.WORKFLOW, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        ArgumentCaptor<RoutingContext> cap = ArgumentCaptor.forClass(RoutingContext.class);
        verify(intentRouter).classifyPlan(cap.capture());
        assertThat(cap.getValue().lockedMode()).isEqualTo(ExecutionMode.WORKFLOW);
    }

    @Test
    void resolve_pro_withSkill_recordsModeTrackL0FinalTraces() {
        ExecutionPlan skillPlan = new ExecutionPlan(
                ExecutionMode.FAST, null, Map.of("skill", "policy-review"), "skill:/mention");
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.of(skillPlan)));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("/policy-review 请事假合规吗", null, ExecutionPreference.PRO, null, null),
                ExecutionPreference.PRO, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.routingTraces()).isNotNull();
        assertThat(plan.routingTraces()).extracting(RoutingTrace::layer)
                .containsExactly("mode", "track", "L0", "final");
        assertThat(plan.routingTraces()).extracting(RoutingTrace::detail)
                .contains("按您选择的「专业」模式处理", "自动匹配技能与助手",
                        "使用技能「policy-review」处理", "使用技能「policy-review」处理");
    }

    @Test
    void resolve_fast_policyRule_recordsRuleTrace() {
        when(skillBindingRoutingPolicy.tryRoute(any())).thenReturn(Mono.just(Optional.empty()));

        ExecutionPlan plan = router.resolve(
                new RoutingContext("差旅办法制度怎么说", null, ExecutionPreference.FAST, null, null),
                ExecutionPreference.FAST, null).block();
        assertThat(plan).isNotNull();
        assertThat(plan.routingTraces()).isNotNull();
        assertThat(plan.routingTraces()).extracting(RoutingTrace::layer)
                .contains("rule", "final");
        assertThat(plan.routingTraces()).anySatisfy(trace ->
                assertThat(trace.detail()).isEqualTo("命中常用处理规则"));
    }

    @Test
    void resolve_workflow_withExplicitId_recordsWorkflowL0Traces() {
        ExecutionPlan plan = router.resolve(
                new RoutingContext("年假", null, ExecutionPreference.WORKFLOW, "knowledge-qa", null),
                ExecutionPreference.WORKFLOW, "knowledge-qa").block();
        assertThat(plan).isNotNull();
        assertThat(plan.routingTraces()).isNotNull();
        assertThat(plan.routingTraces()).extracting(RoutingTrace::layer)
                .containsExactly("mode", "track", "L0", "final");
        assertThat(plan.routingTraces()).extracting(RoutingTrace::detail)
                .contains("直接按流程模板执行", "使用流程「knowledge-qa」", "将执行「knowledge-qa」流程");
    }
}
