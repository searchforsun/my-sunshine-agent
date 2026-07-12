package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanExecutionScheduleTest {

    @Test
    void buildsParallelScheduleFromDualRagFanOut() {
        PlanJson plan = dualRagParallelPlan();
        assertThat(PlanExecutionSchedule.validateParallelTopology(plan)).isNull();

        List<PlanExecutionSchedule.Step> steps = PlanExecutionSchedule.build(plan);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0)).isInstanceOf(PlanExecutionSchedule.Parallel.class);
        PlanExecutionSchedule.Parallel parallel = (PlanExecutionSchedule.Parallel) steps.get(0);
        assertThat(parallel.branchNodeIds()).containsExactly("rag-policy", "rag-finance");
        assertThat(parallel.joinNodeId()).isEqualTo("join-1");
        assertThat(steps.get(1)).isEqualTo(new PlanExecutionSchedule.Single("answer"));

        assertThat(PlanExecutionSchedule.flattenLinearOrder(steps))
                .containsExactly("rag-policy", "rag-finance", "join-1", "answer");
    }

    @Test
    void linearPlanFallsBackToSingleSteps() {
        PlanJson plan = new PlanJson("linear", "test",
                List.of(
                        new PlanNode("rag", "rag", Map.of("topK", "3")),
                        new PlanNode("answer", "answer", Map.of())),
                List.of(
                        new PlanEdge("start", "rag"),
                        new PlanEdge("rag", "answer")));
        List<PlanExecutionSchedule.Step> steps = PlanExecutionSchedule.build(plan);
        assertThat(steps).containsExactly(
                new PlanExecutionSchedule.Single("rag"),
                new PlanExecutionSchedule.Single("answer"));
    }

    @Test
    void rejectsJoinWithSingleIncoming() {
        PlanJson plan = new PlanJson("bad", "test",
                List.of(
                        new PlanNode("rag", "rag", Map.of()),
                        new PlanNode("join-1", "join", Map.of()),
                        new PlanNode("answer", "answer", Map.of())),
                List.of(
                        new PlanEdge("start", "rag"),
                        new PlanEdge("rag", "join-1"),
                        new PlanEdge("join-1", "answer")));
        assertThat(PlanExecutionSchedule.validateParallelTopology(plan))
                .contains("入度须 ≥ 2");
    }

    private static PlanJson dualRagParallelPlan() {
        return new PlanJson("dual-rag", "制度+财务并行检索",
                List.of(
                        new PlanNode("rag-policy", "rag", Map.of("topK", "3"), "制度检索"),
                        new PlanNode("rag-finance", "rag", Map.of("topK", "3"), "财务检索"),
                        new PlanNode("join-1", "join", Map.of(), "汇总"),
                        new PlanNode("answer", "answer", Map.of("prompt",
                                "制度：{{rag-policy.output}}\n财务：{{rag-finance.output}}"), "生成回答")),
                List.of(
                        new PlanEdge("start", "rag-policy"),
                        new PlanEdge("start", "rag-finance"),
                        new PlanEdge("rag-policy", "join-1"),
                        new PlanEdge("rag-finance", "join-1"),
                        new PlanEdge("join-1", "answer")));
    }
}
