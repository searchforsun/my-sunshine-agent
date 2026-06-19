package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.RoutingRuleProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedRouterTest {

    @Test
    void matchesFinanceListPending_withoutLlm() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        RoutingRuleProperties.Rule rule = new RoutingRuleProperties.Rule();
        rule.setId("rule-finance-list-pending");
        rule.setPriority(10);
        rule.setMatch("any");
        rule.setPatterns(List.of("待审批.*报销", "有哪些待审批"));
        RoutingRuleProperties.PlanSpec planSpec = new RoutingRuleProperties.PlanSpec();
        planSpec.setMode("workflow");
        planSpec.setWorkflowId("finance-list");
        planSpec.setParams(Map.of("status", "pending"));
        rule.setPlan(planSpec);
        props.setRules(List.of(rule));

        RuleBasedRouter router = new RuleBasedRouter(props);
        var hit = router.match("有哪些待审批报销");

        assertThat(hit).isPresent();
        assertThat(hit.get().workflowId()).isEqualTo("finance-list");
        assertThat(hit.get().ruleId()).isEqualTo("rule-finance-list-pending");
        assertThat(hit.get().params()).containsEntry("status", "pending");
    }

    @Test
    void complianceRoutesToFinanceSmart_notFinanceList() {
        RoutingRuleProperties props = rulesFromNacosFixture();
        RuleBasedRouter router = new RuleBasedRouter(props);
        assertThat(router.match("待审批报销是否合规"))
                .isPresent()
                .get()
                .satisfies(p -> {
                    assertThat(p.workflowId()).isEqualTo("finance-smart");
                    assertThat(p.ruleId()).isEqualTo("rule-finance-smart-compliance");
                });
    }

    @Test
    void budgetTravelRoutesToKnowledgeQa() {
        RoutingRuleProperties props = rulesFromNacosFixture();
        RuleBasedRouter router = new RuleBasedRouter(props);
        assertThat(router.match("项目预算超支了还能安排出差吗"))
                .isPresent()
                .get()
                .satisfies(p -> {
                    assertThat(p.workflowId()).isEqualTo("knowledge-qa");
                    assertThat(p.ruleId()).isEqualTo("rule-knowledge-budget-travel");
                });
    }

    private static RoutingRuleProperties rulesFromNacosFixture() {
        RoutingRuleProperties props = new RoutingRuleProperties();
        RoutingRuleProperties.Rule smart = new RoutingRuleProperties.Rule();
        smart.setId("rule-finance-smart-compliance");
        smart.setPriority(20);
        smart.setMatch("any");
        smart.setPatterns(List.of("是否合规", "合规吗"));
        RoutingRuleProperties.PlanSpec smartPlan = new RoutingRuleProperties.PlanSpec();
        smartPlan.setMode("workflow");
        smartPlan.setWorkflowId("finance-smart");
        smartPlan.setParams(Map.of("status", "pending"));
        smart.setPlan(smartPlan);

        RoutingRuleProperties.Rule knowledge = new RoutingRuleProperties.Rule();
        knowledge.setId("rule-knowledge-budget-travel");
        knowledge.setPriority(15);
        knowledge.setMatch("any");
        knowledge.setPatterns(List.of("预算.*出差", "预算超支"));
        RoutingRuleProperties.PlanSpec knowledgePlan = new RoutingRuleProperties.PlanSpec();
        knowledgePlan.setMode("workflow");
        knowledgePlan.setWorkflowId("knowledge-qa");
        knowledgePlan.setParams(Map.of());
        knowledge.setPlan(knowledgePlan);

        RoutingRuleProperties.Rule list = new RoutingRuleProperties.Rule();
        list.setId("rule-finance-list-pending");
        list.setPriority(10);
        list.setMatch("any");
        list.setPatterns(List.of("有哪些待审批", "查询待审批", "待审批的.*报销"));
        RoutingRuleProperties.PlanSpec listPlan = new RoutingRuleProperties.PlanSpec();
        listPlan.setMode("workflow");
        listPlan.setWorkflowId("finance-list");
        listPlan.setParams(Map.of("status", "pending"));
        list.setPlan(listPlan);

        props.setRules(List.of(smart, knowledge, list));
        return props;
    }
}
