package com.sunshine.orchestrator.routing;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 路由 golden-set（v6）：用户 mode 钉死；同 mode 规则/L3 仅收集绑定。
 * 对照 docs/routing/routing-golden-set.md（期望 mode 按请求 preference，非自判）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoutingGoldenSetTest {

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

        when(skillBindingParser.parse(anyString(), any(), anyString())).thenAnswer(inv -> SkillBindingOutcome.none(inv.getArgument(0)));
        when(skillBindingParser.stripAtMention(anyString())).thenAnswer(inv -> {
            String msg = inv.getArgument(0);
            if (msg != null && msg.startsWith("@")) {
                int space = msg.indexOf(' ');
                return space > 0 ? msg.substring(space + 1).strip() : "请处理";
            }
            return msg;
        });
        when(skillCatalogService.sanitizeSkillPlan(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(ExecutionPlan.reactFallback("llm")));
    }

    @ParameterizedTest(name = "pro shares track-A rule: {0}")
    @ValueSource(strings = {
            "差旅办法怎么说",
            "制度咨询：报销规定",
            "差旅办法、考勤制度怎么规定的",
            "人事制度有没有规定",
            "报销规定和差旅办法是什么"
    })
    void proPrompts_hitSharedTrackARule(String query) {
        ExecutionPlan plan = forcedRoute(ExecutionPreference.PRO, query, null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-pro");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.REACT_POLICY_QA_ID);
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class));
    }

    @ParameterizedTest(name = "finance-list: {0}")
    @ValueSource(strings = {
            "有哪些待审批报销",
            "查询待审批报销单",
            "列出待审批的差旅报销",
            "待审批付款有哪些"
    })
    void financeListPrompts_withWorkflow(String query) {
        ExecutionPlan plan = forcedRoute(ExecutionPreference.WORKFLOW, query, null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-list");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_LIST_ID);
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class));
    }

    @ParameterizedTest(name = "finance-smart: {0}")
    @ValueSource(strings = {
            "待审批报销是否合规",
            "这笔报销合规吗"
    })
    void financeSmartPrompts_withWorkflow(String query) {
        ExecutionPlan plan = forcedRoute(ExecutionPreference.WORKFLOW, query, null);
        assertThat(plan.workflowId()).isEqualTo("finance-smart");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_SMART_ID);
    }

    @ParameterizedTest(name = "knowledge-qa: {0}")
    @ValueSource(strings = {
            "项目预算超支了还能安排出差吗",
            "出差预算不够怎么办",
            "预算和出差冲突怎么处理"
    })
    void knowledgeQaPrompts_withWorkflow(String query) {
        ExecutionPlan plan = forcedRoute(ExecutionPreference.WORKFLOW, query, null);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.KNOWLEDGE_BUDGET_ID);
    }

    @Test
    void structuralNegative_withPro_fallsThroughToLlmKeepPro() {
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "llm")));

        ExecutionPlan plan = forcedRoute(ExecutionPreference.PRO, "先帮我写一封邮件再总结一下", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-pro");
    }

    @Test
    void mainAcceptance_withPro_mustNotRouteFinanceList() {
        String query = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论";
        ExecutionPlan plan = forcedRoute(ExecutionPreference.PRO, query, null);
        assertThat(plan.mode()).isNotEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "有哪些待审批报销, finance-list",
            "待审批报销是否合规, finance-smart"
    })
    void singleStep_withWorkflow_notPro(String query, String workflowId) {
        ExecutionPlan plan = forcedRoute(ExecutionPreference.WORKFLOW, query, null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo(workflowId);
    }

    @Test
    void unmatchedQuery_defaultFast_pinsForcedReact() {
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "llm:fallback")));

        ExecutionPlan plan = router.route("随便聊聊").block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
        verify(intentRouter).classifyPlan(org.mockito.ArgumentMatchers.<RoutingContext>argThat(
                ctx -> "随便聊聊".equals(ctx.userMessage())));
    }

    @Test
    void atSkill_withPro_bindsSkillKeepsMode() {
        String query = "@finance-analysis 先查制度再拉待办再分析再润色";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "先查制度再拉待办再分析再润色", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse(eq(query), any(), anyString())).thenReturn(binding);

        ExecutionPlan plan = forcedRoute(ExecutionPreference.PRO, query, null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.params()).doesNotContainKey(SkillBindingOutcome.PARAM_PLANNER_MODE);
    }

    @Test
    void autoDiscoverSkillAfterReactClassify_pinsFast() {
        String query = "帮我做一笔报销的合规分析";
        ExecutionPlan llmPlan = new ExecutionPlan(ExecutionMode.FAST, null,
                Map.of(SkillBindingOutcome.PARAM_SKILL, "finance-analysis"), "llm matched skill");
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(llmPlan));
        when(skillCatalogService.sanitizeSkillPlan(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
    }

    @Test
    void atSkillSingleStep_withFast_overridesFinanceSmartRule() {
        String query = "@finance-analysis 这笔报销是否合规";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "这笔报销是否合规", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse(eq(query), any(), anyString())).thenReturn(binding);
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(
                        ExecutionMode.FAST, null, Map.of("status", "pending"), "llm")));

        ExecutionPlan plan = forcedRoute(ExecutionPreference.FAST, query, null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.workflowId()).isNull();
    }

    // --- §J Chat executionPreference / executionMode 钉死 ---

    @Test
    void forcedJ2_react() {
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(ExecutionPlan.reactFallback("llm")));
        ExecutionPlan plan = forcedRoute(ExecutionPreference.FAST, "待审批是否合规", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.reason()).isEqualTo("user:forced-fast");
    }

    @Test
    void forcedJ3_workflow_knowledgeQa() {
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(
                        ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "llm")));
        ExecutionPlan plan = forcedRoute(ExecutionPreference.WORKFLOW, "青松假有多少天、怎么申请", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("user:forced-workflow");
    }

    @Test
    void forcedJ4_pro() {
        ExecutionPlan plan = forcedRoute(ExecutionPreference.PRO, "先查制度再查待审批", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-pro");
    }

    @Test
    void forcedJ5_workflow_ignoresAtSkill() {
        String query = "@policy-review 青松假有多少天、怎么申请";
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(
                        ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "llm")));
        ExecutionPlan plan = forcedRoute(ExecutionPreference.WORKFLOW, query, null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("user:forced-workflow");
        assertThat(plan.params()).doesNotContainKey(SkillBindingOutcome.PARAM_SKILL);
    }

    @Test
    void forcedJ6_pro_mergesAtSkillParams() {
        String query = "@finance-analysis 是否合规";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "是否合规", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse(eq(query), any(), anyString())).thenReturn(binding);
        ExecutionPlan plan = forcedRoute(ExecutionPreference.PRO, query, null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.reason()).isEqualTo("user:forced-pro");
        assertThat(plan.params()).containsEntry(SkillBindingOutcome.PARAM_SKILL, "finance-analysis");
    }

    // --- §I Workflow `#` 绑定（仅 workflow mode） ---

    @Test
    void workflowI1_hashKnowledgeQa() {
        when(workflowCatalog.isKnownWorkflow("knowledge-qa")).thenReturn(true);
        ExecutionPlan plan = forcedRoute(
                ExecutionPreference.WORKFLOW, "#knowledge-qa 青松假有多少天、怎么申请", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("workflow:#mention");
        assertThat(plan.params().get("effectiveQuery")).isEqualTo("青松假有多少天、怎么申请");
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class));
    }

    @Test
    void workflowI2_hashKnowledgeQaReimbursement() {
        when(workflowCatalog.isKnownWorkflow("knowledge-qa")).thenReturn(true);
        ExecutionPlan plan = forcedRoute(
                ExecutionPreference.WORKFLOW, "#knowledge-qa 市内网约车报销上限多少", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("workflow:#mention");
    }

    @Test
    void workflowI3_hashFinanceSmartOverridesRules() {
        when(workflowCatalog.isKnownWorkflow("finance-smart")).thenReturn(true);
        ExecutionPlan plan = forcedRoute(
                ExecutionPreference.WORKFLOW, "#finance-smart 待审批报销是否合规", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-smart");
        assertThat(plan.reason()).isEqualTo("workflow:#mention");
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class));
    }

    @Test
    void workflowI4_unknownWorkflowNotFound() {
        when(workflowCatalog.isKnownWorkflow("not-exists")).thenReturn(false);
        assertThatThrownBy(() -> forcedRoute(ExecutionPreference.WORKFLOW, "#not-exists 测试", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.WORKFLOW_NOT_FOUND);
    }

    @Test
    void workflowI6_clientWorkflowIdBindsWithoutLlm() {
        when(workflowCatalog.isKnownWorkflow("security-analyze")).thenReturn(true);
        ExecutionPlan plan = forcedRoute(ExecutionPreference.WORKFLOW, "请继续分析", "security-analyze");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("security-analyze");
        assertThat(plan.reason()).isEqualTo("workflow:client");
        assertThat(plan.params().get("effectiveQuery")).isEqualTo("请继续分析");
        verify(intentRouter, never()).classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class));
    }

    @Test
    void workflowI5_atKnowledgeQaNotWorkflow_underFast() {
        String query = "@knowledge-qa 测试";
        when(skillBindingParser.parse(eq(query), any(), anyString())).thenReturn(SkillBindingOutcome.none(query));
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "llm")));
        ExecutionPlan plan = forcedRoute(ExecutionPreference.FAST, query, null);
        assertThat(plan.workflowId()).isNull();
        assertThat(plan.mode()).isNotEqualTo(ExecutionMode.WORKFLOW);
    }

    private ExecutionPlan forcedRoute(ExecutionPreference preference, String query, String workflowId) {
        return router.route(new RoutingContext(query, null, preference, workflowId, null)).block();
    }
}
