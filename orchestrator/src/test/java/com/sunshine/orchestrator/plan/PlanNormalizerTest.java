package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanNormalizerTest {

    @Test
    void infersLinearEdgesAndAppendsAnswer() {
        PlanJson raw = new PlanJson("p1", "r",
                List.of(
                        new PlanNode("n1", "rag", Map.of()),
                        new PlanNode("n2", "tool", Map.of("tool", "sdk__sunshine-finance__list_my_expenses"))),
                List.of());
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(normalized.edges()).hasSize(3);
        assertThat(PlanLinearizer.linearOrder(normalized))
                .containsExactly("n1", "n2", PlanNormalizer.ANSWER_NODE_ID);
        assertThat(normalized.nodesById().get(PlanNormalizer.ANSWER_NODE_ID).type()).isEqualTo("answer");
    }

    @Test
    void appendsAnswerWhenEdgesPresent() {
        PlanJson raw = new PlanJson("p1", "r",
                List.of(new PlanNode("n1", "rag", Map.of())),
                List.of(new PlanEdge("start", "n1")));
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(normalized.edges()).hasSize(2);
        assertThat(normalized.edges()).contains(new PlanEdge("n1", PlanNormalizer.ANSWER_NODE_ID));
    }

    @Test
    void appendsAnswerToAllSinksForExclusiveArms() {
        PlanJson raw = new PlanJson("p1", "exclusive",
                List.of(
                        new PlanNode("xg-1", "exclusive-gateway", Map.of(), "条件分支"),
                        new PlanNode("rag-hit", "rag", Map.of("topK", "3"), "命中检索"),
                        new PlanNode("rag-miss", "rag", Map.of("topK", "3"), "兜底检索")),
                List.of(
                        new PlanEdge("start", "xg-1"),
                        new PlanEdge("xg-1", "rag-hit",
                                PlanEdgeConditionGroup.single(new PlanEdgeCondition("{{start.userQuery}}", "contains", "报销")), false),
                        new PlanEdge("xg-1", "rag-miss", null, true)));
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(normalized.edges()).contains(
                new PlanEdge("rag-hit", PlanNormalizer.ANSWER_NODE_ID),
                new PlanEdge("rag-miss", PlanNormalizer.ANSWER_NODE_ID));
    }

    @Test
    void appendsAnswerAfterJoinForParallelPlan() {
        PlanJson raw = new PlanJson("p1", "parallel",
                List.of(
                        new PlanNode("pg-1", "parallel-gateway", Map.of(), "并行分叉"),
                        new PlanNode("rag-a", "rag", Map.of("topK", "3"), "制度检索"),
                        new PlanNode("rag-b", "rag", Map.of("topK", "3"), "财务检索"),
                        new PlanNode("join-1", "join", Map.of(), "汇总")),
                List.of(
                        new PlanEdge("start", "pg-1"),
                        new PlanEdge("pg-1", "rag-a"),
                        new PlanEdge("pg-1", "rag-b"),
                        new PlanEdge("rag-a", "join-1"),
                        new PlanEdge("rag-b", "join-1")));
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(normalized.edges()).contains(new PlanEdge("join-1", PlanNormalizer.ANSWER_NODE_ID));
        assertThat(normalized.edges().stream().filter(e -> PlanNormalizer.ANSWER_NODE_ID.equals(e.to())).count())
                .isEqualTo(1);
    }
}
