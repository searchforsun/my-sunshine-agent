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
        assertThat(PlanExecutionSchedule.validateParallelTopology(plan).message())
                .contains("入度须 ≥ 2");
    }

    @Test
    void buildsParallelScheduleWithExplicitParallelGateway() {
        PlanJson plan = dualRagWithParallelGateway();
        assertThat(PlanExecutionSchedule.validateParallelTopology(plan)).isNull();

        List<PlanExecutionSchedule.Step> steps = PlanExecutionSchedule.build(plan);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).isEqualTo(new PlanExecutionSchedule.Single("pg-1"));
        assertThat(steps.get(1)).isInstanceOf(PlanExecutionSchedule.Parallel.class);
        PlanExecutionSchedule.Parallel parallel = (PlanExecutionSchedule.Parallel) steps.get(1);
        assertThat(parallel.branchNodeIds()).containsExactly("rag-policy", "rag-finance");
        assertThat(parallel.joinNodeId()).isEqualTo("join-1");
        assertThat(steps.get(2)).isEqualTo(new PlanExecutionSchedule.Single("answer"));
    }

    @Test
    void buildsExclusiveScheduleWithEdgeConditions() {
        PlanJson plan = exclusivePlan();
        assertThat(PlanExecutionSchedule.validateExclusiveTopology(plan)).isNull();
        List<PlanExecutionSchedule.Step> steps = PlanExecutionSchedule.build(plan);
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0)).isEqualTo(new PlanExecutionSchedule.Single("xg-1"));
        assertThat(steps.get(1)).isInstanceOf(PlanExecutionSchedule.Exclusive.class);
        PlanExecutionSchedule.Exclusive exclusive = (PlanExecutionSchedule.Exclusive) steps.get(1);
        assertThat(exclusive.gatewayNodeId()).isEqualTo("xg-1");
        assertThat(exclusive.arms()).hasSize(2);
        assertThat(exclusive.arms().get(0).targetNodeId()).isEqualTo("rag-hit");
        assertThat(exclusive.arms().get(0).isDefault()).isFalse();
        assertThat(exclusive.arms().get(1).isDefault()).isTrue();
        assertThat(steps.get(2)).isEqualTo(new PlanExecutionSchedule.Single("answer"));
    }

    @Test
    void rejectsExclusiveWithoutDefault() {
        PlanJson plan = new PlanJson("bad-xg", "test",
                List.of(
                        new PlanNode("xg-1", "exclusive-gateway", Map.of()),
                        new PlanNode("a", "rag", Map.of()),
                        new PlanNode("b", "rag", Map.of()),
                        new PlanNode("answer", "answer", Map.of())),
                List.of(
                        new PlanEdge("start", "xg-1"),
                        new PlanEdge("xg-1", "a", new PlanEdgeCondition("{{a.output}}", "not_empty", ""), false),
                        new PlanEdge("xg-1", "b", new PlanEdgeCondition("{{a.output}}", "empty", ""), false),
                        new PlanEdge("a", "answer"),
                        new PlanEdge("b", "answer")));
        assertThat(PlanExecutionSchedule.validateExclusiveTopology(plan).message())
                .contains("须恰好 1 条 default");
    }

    @Test
    void buildsLoopScheduleAndValidates() {
        PlanJson plan = loopPlan();
        assertThat(PlanExecutionSchedule.validateLoopTopology(plan)).isNull();
        List<PlanExecutionSchedule.Step> steps = PlanExecutionSchedule.build(plan);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0)).isInstanceOf(PlanExecutionSchedule.Loop.class);
        PlanExecutionSchedule.Loop loop = (PlanExecutionSchedule.Loop) steps.get(0);
        assertThat(loop.loopNodeId()).isEqualTo("loop-1");
        assertThat(loop.bodyNodeIds()).containsExactly("rag-body");
        assertThat(steps.get(1)).isEqualTo(new PlanExecutionSchedule.Single("answer"));
    }

    @Test
    void rejectsLoopWithoutBody() {
        PlanJson plan = new PlanJson("bad-loop", "test",
                List.of(
                        new PlanNode("loop-1", "loop", Map.of(
                                "condition.left", "{{start.userQuery}}",
                                "condition.op", "contains",
                                "condition.right", "x",
                                "maxIterations", "3",
                                "onMaxIterations", "fail_fast")),
                        new PlanNode("answer", "answer", Map.of())),
                List.of(
                        new PlanEdge("start", "loop-1"),
                        new PlanEdge("loop-1", "answer")));
        assertThat(PlanExecutionSchedule.validateLoopTopology(plan).message()).contains("无框内 body");
    }

    private static PlanJson loopPlan() {
        return new PlanJson("loop-ok", "test",
                List.of(
                        new PlanNode("loop-1", "loop", Map.of(
                                "condition.left", "{{start.userQuery}}",
                                "condition.op", "contains",
                                "condition.right", "继续",
                                "maxIterations", "3",
                                "onMaxIterations", "exit",
                                "retry.maxAttempts", "1",
                                "retry.backoffMs", "500",
                                "retry.onFailure", "fail_fast"), "循环", null),
                        new PlanNode("rag-body", "rag", Map.of("query", "{{start.userQuery}}", "topK", "3"),
                                "框内检索", "loop-1"),
                        new PlanNode("answer", "answer", Map.of(), "回答", null)),
                List.of(
                        new PlanEdge("start", "loop-1"),
                        new PlanEdge("loop-1", "answer")));
    }

    private static PlanJson exclusivePlan() {
        return new PlanJson("exclusive", "条件分支",
                List.of(
                        new PlanNode("xg-1", "exclusive-gateway", Map.of(), "条件分支"),
                        new PlanNode("rag-hit", "rag", Map.of("topK", "3"), "命中检索"),
                        new PlanNode("rag-miss", "rag", Map.of("topK", "3"), "兜底检索"),
                        new PlanNode("answer", "answer", Map.of(), "回答")),
                List.of(
                        new PlanEdge("start", "xg-1"),
                        new PlanEdge("xg-1", "rag-hit",
                                new PlanEdgeCondition("{{start.userQuery}}", "contains", "报销"), false),
                        new PlanEdge("xg-1", "rag-miss", null, true),
                        new PlanEdge("rag-hit", "answer"),
                        new PlanEdge("rag-miss", "answer")));
    }

    private static PlanJson dualRagWithParallelGateway() {
        return new PlanJson("dual-rag-pg", "BPMN 并行网关",
                List.of(
                        new PlanNode("pg-1", "parallel-gateway", Map.of(), "并行分叉"),
                        new PlanNode("rag-policy", "rag", Map.of("topK", "3"), "制度检索"),
                        new PlanNode("rag-finance", "rag", Map.of("topK", "3"), "财务检索"),
                        new PlanNode("join-1", "join", Map.of(), "并行汇总"),
                        new PlanNode("answer", "answer", Map.of(), "生成回答")),
                List.of(
                        new PlanEdge("start", "pg-1"),
                        new PlanEdge("pg-1", "rag-policy"),
                        new PlanEdge("pg-1", "rag-finance"),
                        new PlanEdge("rag-policy", "join-1"),
                        new PlanEdge("rag-finance", "join-1"),
                        new PlanEdge("join-1", "answer")));
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
