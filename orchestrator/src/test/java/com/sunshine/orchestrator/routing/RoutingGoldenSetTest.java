package com.sunshine.orchestrator.routing;



import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import com.sunshine.orchestrator.routing.policy.LlmClassifierRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.UnifiedRuleRoutingPolicy;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.skill.SkillBindingSource;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.skill.SkillDiscoveryService;

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



import java.util.List;
import java.util.Map;
import java.util.Optional;



import com.sunshine.orchestrator.routing.policy.RoutingContext;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.never;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;



/**

 * 路由 golden-set — 与 docs/routing/routing-golden-set.md 对照。

 */

@ExtendWith(MockitoExtension.class)

@MockitoSettings(strictness = Strictness.LENIENT)

class RoutingGoldenSetTest {



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



    private ExecutionPlanRouter router;



    @BeforeEach

    void setUp() {
        PromptCatalogHolder catalogHolder = RoutingCatalogFixtures.seedHolder();
        SkillBindingRoutingPolicy skillPolicy = new SkillBindingRoutingPolicy(skillBindingParser, catalogHolder);
        WorkflowBindingParser workflowBindingParser = new WorkflowBindingParser(workflowCatalog);
        var chain = new RoutingPolicyChain(List.of(
                new WorkflowBindingRoutingPolicy(workflowBindingParser),
                skillPolicy,
                new UnifiedRuleRoutingPolicy(catalogHolder),
                new LlmClassifierRoutingPolicy(intentRouter, queryRewriteService)));
        router = new ExecutionPlanRouter(chain, new SkillDiscoveryService(skillCatalogService),
                new ForcedExecutionRouter(skillPolicy, catalogHolder, intentRouter),
                skillBindingParser);

        when(skillBindingParser.parse(anyString())).thenAnswer(inv -> SkillBindingOutcome.none(inv.getArgument(0)));
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
    }



    @ParameterizedTest(name = "plan-workflow: {0}")

    @ValueSource(strings = {

            "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论",

            "先查一下年假制度，再帮我看看待审批的请假单有没有问题",

            "先检索报销政策，再列出待审批付款，然后逐条审查是否合规",

            "分步处理：先知识库找差旅标准，再查财务待审批报销",

            "请完整处理待审批差旅报销：先对照制度，再查单据并给出评估结论",

            "给我一套差旅报销的分析流程：制度检索、待办查询、合规结论"

    })

    void planWorkflowPrompts(String query) {

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);

        assertThat(plan.reason()).isEqualTo("rule:" + RoutingCatalogFixtures.STRUCTURAL_ID);

        verify(intentRouter, never()).classifyPlan(anyString());

    }



    @ParameterizedTest(name = "finance-list: {0}")

    @ValueSource(strings = {

            "有哪些待审批报销",

            "查询待审批报销单",

            "列出待审批的差旅报销",

            "待审批付款有哪些"

    })

    void financeListPrompts(String query) {

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);

        assertThat(plan.workflowId()).isEqualTo("finance-list");

        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_LIST_ID);

        verify(intentRouter, never()).classifyPlan(anyString());

    }



    @ParameterizedTest(name = "finance-smart: {0}")

    @ValueSource(strings = {

            "待审批报销是否合规",

            "这笔报销合规吗"

    })

    void financeSmartPrompts(String query) {

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.workflowId()).isEqualTo("finance-smart");

        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.FINANCE_SMART_ID);

    }



    @ParameterizedTest(name = "knowledge-qa: {0}")

    @ValueSource(strings = {

            "项目预算超支了还能安排出差吗",

            "出差预算不够怎么办",

            "预算和出差冲突怎么处理"

    })

    void knowledgeQaPrompts(String query) {

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");

        assertThat(plan.ruleId()).isEqualTo(RoutingCatalogFixtures.KNOWLEDGE_BUDGET_ID);

    }



    @Test

    void structuralNegative_insufficientDomainGroups_fallsThroughToLlm() {

        when(queryRewriteService.shouldRewriteIntent("先帮我写一封邮件再总结一下")).thenReturn(false);

        ExecutionPlan llmPlan = new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "llm");

        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(llmPlan));

        assertThat(router.route("先帮我写一封邮件再总结一下").block()).isEqualTo(llmPlan);

    }



    @Test

    void mainAcceptance_mustNotRouteFinanceList() {

        String query = "先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论";

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isNotEqualTo(ExecutionMode.WORKFLOW);

        assertThat(plan.workflowId()).isNull();

    }



    @ParameterizedTest

    @CsvSource({

            "有哪些待审批报销, finance-list",

            "待审批报销是否合规, finance-smart"

    })

    void singleStepMustNotBePlanWorkflow(String query, String workflowId) {

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);

        assertThat(plan.workflowId()).isEqualTo(workflowId);

    }



    @Test

    void unmatchedQuery_fallsThroughToLlm() {

        when(queryRewriteService.shouldRewriteIntent("随便聊聊")).thenReturn(false);

        ExecutionPlan llmPlan = new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "llm:fallback");

        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(llmPlan));

        assertThat(router.route("随便聊聊").block()).isEqualTo(llmPlan);

        verify(intentRouter).classifyPlan(org.mockito.ArgumentMatchers.<RoutingContext>argThat(
                ctx -> "随便聊聊".equals(ctx.userMessage())));

    }

    @Test
    void atSkillMultiStepRoutesToPlanWorkflow5B() {
        String query = "@finance-analysis 先查制度再拉待办再分析再润色";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "先查制度再拉待办再分析再润色", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse(query)).thenReturn(binding);

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_PLANNER_MODE))
                .isEqualTo(SkillBindingOutcome.PLANNER_MODE_SKILL_DRIVEN);
        verify(intentRouter, never()).classifyPlan(anyString());
    }

    @Test
    void autoDiscoverSkillAfterReactClassify() {
        String query = "帮我做一笔报销的合规分析";
        when(queryRewriteService.shouldRewriteIntent(query)).thenReturn(false);
        ExecutionPlan llmPlan = new ExecutionPlan(ExecutionMode.REACT, null,
                Map.of(SkillBindingOutcome.PARAM_SKILL, "finance-analysis"), "llm matched skill");
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(llmPlan));
        when(skillCatalogService.sanitizeSkillPlan(llmPlan)).thenReturn(llmPlan);

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.reason()).isEqualTo("llm matched skill");
    }

    @Test
    void atSkillSingleStepOverridesFinanceSmartRule() {
        String query = "@finance-analysis 这笔报销是否合规";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "这笔报销是否合规", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse(query)).thenReturn(binding);

        ExecutionPlan plan = router.route(query).block();

        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("finance-analysis");
        assertThat(plan.workflowId()).isNull();
    }

    // --- §J Chat executionPreference 强制路由（routing-golden-set.md） ---

    @Test
    void forcedJ2_react() {
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(ExecutionPlan.reactFallback("llm")));
        ExecutionPlan plan = forcedRoute(ExecutionPreference.REACT, "待审批是否合规", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        assertThat(plan.reason()).isEqualTo("user:forced-react");
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
    void forcedJ4_planWorkflow() {
        ExecutionPlan plan = forcedRoute(
                ExecutionPreference.PLAN_WORKFLOW, "先查制度再查待审批", null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
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
    void forcedJ6_planWorkflow_mergesAtSkillParams() {
        String query = "@finance-analysis 是否合规";
        SkillBindingOutcome binding = SkillBindingOutcome.bound(
                "finance-analysis", "是否合规", SkillBindingSource.AT_MENTION);
        when(skillBindingParser.parse(query)).thenReturn(binding);
        ExecutionPlan plan = forcedRoute(ExecutionPreference.PLAN_WORKFLOW, query, null);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.reason()).isEqualTo("user:forced-plan-workflow");
        assertThat(plan.params()).containsEntry(SkillBindingOutcome.PARAM_SKILL, "finance-analysis");
    }

    @ParameterizedTest(name = "peerCollab E1: {0}")
    @ValueSource(strings = {
            "请制度专家和财务专家分别审查这笔报销是否合规，并互相验证",
            "从合规和财务两个角度交叉审查上述制度条款"
    })


    // --- §I Workflow `#` 绑定（routing-golden-set.md） ---

    @Test
    void workflowI1_hashKnowledgeQa() {
        when(workflowCatalog.isKnownWorkflow("knowledge-qa")).thenReturn(true);
        ExecutionPlan plan = router.route("#knowledge-qa 青松假有多少天、怎么申请").block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("workflow:#mention");
        assertThat(plan.params().get("effectiveQuery")).isEqualTo("青松假有多少天、怎么申请");
        verify(intentRouter, never()).classifyPlan(anyString());
    }

    @Test
    void workflowI2_hashKnowledgeQaReimbursement() {
        when(workflowCatalog.isKnownWorkflow("knowledge-qa")).thenReturn(true);
        ExecutionPlan plan = router.route("#knowledge-qa 市内网约车报销上限多少").block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("workflow:#mention");
    }

    @Test
    void workflowI3_hashFinanceSmartOverridesRules() {
        when(workflowCatalog.isKnownWorkflow("finance-smart")).thenReturn(true);
        ExecutionPlan plan = router.route("#finance-smart 待审批报销是否合规").block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-smart");
        assertThat(plan.reason()).isEqualTo("workflow:#mention");
        verify(intentRouter, never()).classifyPlan(anyString());
    }

    @Test
    void workflowI4_unknownWorkflowNotFound() {
        when(workflowCatalog.isKnownWorkflow("not-exists")).thenReturn(false);
        assertThatThrownBy(() -> router.route("#not-exists 测试").block())
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.WORKFLOW_NOT_FOUND);
    }

    @Test
    void workflowI6_clientWorkflowIdBindsWithoutLlm() {
        when(workflowCatalog.isKnownWorkflow("security-analyze")).thenReturn(true);
        ExecutionPlan plan = router.route(new RoutingContext(
                "请继续分析", null, ExecutionPreference.AUTO, "security-analyze", null)).block();
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("security-analyze");
        assertThat(plan.reason()).isEqualTo("workflow:client");
        assertThat(plan.params().get("effectiveQuery")).isEqualTo("请继续分析");
        verify(intentRouter, never()).classifyPlan(anyString());
    }

    @Test
    void workflowI5_atKnowledgeQaNotWorkflow() {
        String query = "@knowledge-qa 测试";
        when(skillBindingParser.parse(query)).thenReturn(SkillBindingOutcome.none(query));
        when(intentRouter.classifyPlan(org.mockito.ArgumentMatchers.any(RoutingContext.class)))
                .thenReturn(Mono.just(new ExecutionPlan(
                ExecutionMode.REACT, null, Map.of(), "llm")));
        ExecutionPlan plan = router.route(query).block();
        assertThat(plan.workflowId()).isNull();
        assertThat(plan.mode()).isNotEqualTo(ExecutionMode.WORKFLOW);
    }








    private ExecutionPlan forcedRoute(ExecutionPreference preference, String query, String workflowId) {
        return router.route(new RoutingContext(query, null, preference, workflowId, null)).block();
    }

}


