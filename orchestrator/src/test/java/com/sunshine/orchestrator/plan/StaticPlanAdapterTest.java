package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticPlanAdapterTest {

    @Test
    void convertsStaticDefinitionToPlanJson() {
        WorkflowDefinition def = WorkflowDefinition.from("finance-list", List.of(
                new NodeSpec("start", "start", Map.of()),
                new NodeSpec("finance-list", "tool", Map.of("tool", "list_finance_messages"), "查询待审批"),
                new NodeSpec("answer", "answer", Map.of("prompt", "p"), "生成回答")
        ), List.of("start", "finance-list", "answer"));

        PlanJson plan = StaticPlanAdapter.from(def, "查待办");

        assertThat(plan.planId()).isNull();
        assertThat(plan.reason()).isEqualTo("查待办");
        assertThat(plan.nodes()).hasSize(3);
        assertThat(plan.edges()).containsExactly(
                new PlanEdge("start", "finance-list"),
                new PlanEdge("finance-list", "answer"));
        assertThat(PlanTimeline.planChainSummary(plan)).isEqualTo("查询待审批");
    }
}
