package com.sunshine.orchestrator.routing;



import com.sunshine.orchestrator.agent.IntentRouter;

import com.sunshine.orchestrator.config.RoutingRuleProperties;

import com.sunshine.orchestrator.routing.policy.GoldenRuleRoutingPolicy;

import com.sunshine.orchestrator.routing.policy.LlmClassifierRoutingPolicy;

import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;

import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;

import com.sunshine.orchestrator.routing.policy.StructuralRoutingPolicy;

import com.sunshine.orchestrator.rewrite.QueryRewriteService;

import com.sunshine.orchestrator.skill.SkillBindingOutcome;

import com.sunshine.orchestrator.skill.SkillBindingParser;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.params.ParameterizedTest;

import org.junit.jupiter.params.provider.CsvSource;

import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.Mock;

import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Mono;



import java.util.List;

import java.util.Map;



import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.never;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;



/**

 * 路由 golden-set — 与 docs/routing/routing-golden-set.md 对照。

 */

@ExtendWith(MockitoExtension.class)

class RoutingGoldenSetTest {



    @Mock

    private SkillBindingParser skillBindingParser;

    @Mock

    private IntentRouter intentRouter;

    @Mock

    private QueryRewriteService queryRewriteService;



    private ExecutionPlanRouter router;



    @BeforeEach

    void setUp() {

        RoutingRuleProperties routingProps = nacosRulesFixture();

        RuleBasedRouter ruleRouter = new RuleBasedRouter(routingProps);

        StructuralPlanMatcher structuralMatcher = new StructuralPlanMatcher(routingProps);

        var chain = new RoutingPolicyChain(List.of(

                new SkillBindingRoutingPolicy(skillBindingParser),

                new StructuralRoutingPolicy(structuralMatcher),

                new GoldenRuleRoutingPolicy(ruleRouter, structuralMatcher),

                new LlmClassifierRoutingPolicy(intentRouter, queryRewriteService)));

        router = new ExecutionPlanRouter(chain);

        when(skillBindingParser.parse(anyString())).thenAnswer(inv -> SkillBindingOutcome.none(inv.getArgument(0)));

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

        assertThat(plan.reason()).isEqualTo("structural:multi-step-plan");

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

        assertThat(plan.ruleId()).isEqualTo("rule-finance-list-pending");

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

        assertThat(plan.ruleId()).isEqualTo("rule-finance-smart-compliance");

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

        assertThat(plan.ruleId()).isEqualTo("rule-knowledge-budget-travel");

    }



    @Test

    void structuralNegative_insufficientDomainGroups_fallsThroughToLlm() {

        when(queryRewriteService.shouldRewriteIntent("先帮我写一封邮件再总结一下")).thenReturn(false);

        ExecutionPlan llmPlan = new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "llm");

        when(intentRouter.classifyPlan("先帮我写一封邮件再总结一下")).thenReturn(Mono.just(llmPlan));

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

        when(intentRouter.classifyPlan("随便聊聊")).thenReturn(Mono.just(llmPlan));

        assertThat(router.route("随便聊聊").block()).isEqualTo(llmPlan);

        verify(intentRouter).classifyPlan("随便聊聊");

    }



    private static RoutingRuleProperties nacosRulesFixture() {

        RoutingRuleProperties props = new RoutingRuleProperties();

        RoutingRuleProperties.Rule smart = new RoutingRuleProperties.Rule();

        smart.setId("rule-finance-smart-compliance");

        smart.setPriority(20);

        smart.setMatch("any");

        smart.setPatterns(List.of("是否合规", "合规吗", "合不合规", "对比制度"));

        RoutingRuleProperties.PlanSpec smartPlan = new RoutingRuleProperties.PlanSpec();

        smartPlan.setMode("workflow");

        smartPlan.setWorkflowId("finance-smart");

        smartPlan.setParams(Map.of("status", "pending"));

        smart.setPlan(smartPlan);

        RoutingRuleProperties.Rule knowledge = new RoutingRuleProperties.Rule();

        knowledge.setId("rule-knowledge-budget-travel");

        knowledge.setPriority(15);

        knowledge.setMatch("any");

        knowledge.setPatterns(List.of("预算.*出差", "出差.*预算", "预算超支", "预算不够.*出差"));

        RoutingRuleProperties.PlanSpec knowledgePlan = new RoutingRuleProperties.PlanSpec();

        knowledgePlan.setMode("workflow");

        knowledgePlan.setWorkflowId("knowledge-qa");

        knowledgePlan.setParams(Map.of());

        knowledge.setPlan(knowledgePlan);

        RoutingRuleProperties.Rule list = new RoutingRuleProperties.Rule();

        list.setId("rule-finance-list-pending");

        list.setPriority(10);

        list.setMatch("any");

        list.setPatterns(List.of("有哪些待审批", "查询待审批", "列出待审批", "待审批的.*报销", "待审批.*付款"));

        RoutingRuleProperties.PlanSpec listPlan = new RoutingRuleProperties.PlanSpec();

        listPlan.setMode("workflow");

        listPlan.setWorkflowId("finance-list");

        listPlan.setParams(Map.of("status", "pending"));

        list.setPlan(listPlan);

        props.setRules(List.of(smart, knowledge, list));

        return props;

    }

}


