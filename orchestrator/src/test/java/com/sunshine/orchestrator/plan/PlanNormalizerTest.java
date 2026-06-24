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
                        new PlanNode("n2", "tool", Map.of("tool", "list_finance_messages"))),
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
}
