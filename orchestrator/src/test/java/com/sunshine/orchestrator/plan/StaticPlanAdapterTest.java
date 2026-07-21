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
                new NodeSpec("finance-list", "tool", Map.of("tool", "sdk__sunshine-finance__list_my_expenses"), "查询待审批"),
                new NodeSpec("answer", "answer", Map.of("prompt", "p"), "生成回答")
        ), List.of("finance-list", "answer"));

        PlanJson plan = StaticPlanAdapter.from(def, "查待办");

        assertThat(plan.planId()).isNull();
        assertThat(plan.reason()).isEqualTo("查待办");
        assertThat(plan.nodes()).hasSize(3);
        assertThat(plan.nodes().get(0).id()).isEqualTo("start");
        assertThat(plan.edges()).containsExactly(
                new PlanEdge("start", "finance-list"),
                new PlanEdge("finance-list", "answer"));
        assertThat(PlanTimeline.planChainSummary(plan)).isEqualTo("查询待审批");
    }

    @Test
    void fromStoredPlanPreservesParallelEdgesAndLayout() {
        PlanJson stored = new PlanJson("wf", "并行双检索",
                List.of(
                        new PlanNode("start", "start", Map.of(), "开始"),
                        new PlanNode("pg-a1", "parallel-gateway", Map.of(), "并行分叉"),
                        new PlanNode("rag-a", "rag", Map.of(), "制度检索"),
                        new PlanNode("rag-b", "rag", Map.of(), "财务检索"),
                        new PlanNode("join-c", "join", Map.of(), "并行汇总"),
                        new PlanNode("answer", "answer", Map.of(), "生成回答")
                ),
                List.of(
                        new PlanEdge("start", "pg-a1"),
                        new PlanEdge("pg-a1", "rag-a"),
                        new PlanEdge("pg-a1", "rag-b"),
                        new PlanEdge("rag-a", "join-c"),
                        new PlanEdge("rag-b", "join-c"),
                        new PlanEdge("join-c", "answer")
                ),
                Map.of(
                        "pg-a1", new PlanLayoutPoint(322, 80),
                        "rag-a", new PlanLayoutPoint(430, 28)
                ));

        PlanJson snapshot = StaticPlanAdapter.fromStoredPlan(stored, "路由命中");

        assertThat(snapshot.planId()).isNull();
        assertThat(snapshot.reason()).isEqualTo("路由命中");
        assertThat(snapshot.edges()).containsExactlyInAnyOrder(
                new PlanEdge("start", "pg-a1"),
                new PlanEdge("pg-a1", "rag-a"),
                new PlanEdge("pg-a1", "rag-b"),
                new PlanEdge("rag-a", "join-c"),
                new PlanEdge("rag-b", "join-c"),
                new PlanEdge("join-c", "answer"));
        assertThat(snapshot.layout()).containsEntry("pg-a1", new PlanLayoutPoint(322, 80));
        assertThat(snapshot.layout()).containsEntry("rag-a", new PlanLayoutPoint(430, 28));
    }
}
