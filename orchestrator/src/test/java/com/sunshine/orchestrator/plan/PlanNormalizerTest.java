package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanNormalizerTest {

    @Test
    void infersLinearEdgesWhenMissing() {
        PlanJson raw = new PlanJson("p1", "r",
                List.of(
                        new PlanNode("n1", "rag", Map.of()),
                        new PlanNode("n2", "tool", Map.of("tool", "list_finance_messages")),
                        new PlanNode("n3", "answer", Map.of())),
                List.of());
        PlanJson normalized = PlanNormalizer.normalize(raw);
        assertThat(normalized.edges()).hasSize(3);
        assertThat(PlanLinearizer.linearOrder(normalized))
                .containsExactly("n1", "n2", "n3");
    }

    @Test
    void keepsExistingEdges() {
        PlanJson raw = new PlanJson("p1", "r",
                List.of(new PlanNode("n1", "llm", Map.of())),
                List.of(new PlanEdge("start", "n1")));
        assertThat(PlanNormalizer.normalize(raw).edges()).hasSize(1);
    }
}
