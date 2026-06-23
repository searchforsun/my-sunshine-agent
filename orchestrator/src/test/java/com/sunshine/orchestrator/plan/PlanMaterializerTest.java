package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.execution.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanMaterializerTest {

    @Mock
    private PlanDisplayNameEnricher displayNameEnricher;

    @InjectMocks
    private PlanMaterializer materializer;

    @Test
    void materializesLinearWorkflowDefinition() {
        PlanJson plan = new PlanJson("dyn-1", "test",
                List.of(
                        new PlanNode("n1", "rag", Map.of("topK", "3")),
                        new PlanNode("n2", "llm", Map.of()),
                        new PlanNode("n3", "answer", Map.of())),
                List.of(
                        new PlanEdge("start", "n1"),
                        new PlanEdge("n1", "n2"),
                        new PlanEdge("n2", "n3")));
        when(displayNameEnricher.enrich(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowDefinition def = materializer.materialize(plan);
        assertThat(def.id()).isEqualTo("dyn-1");
        assertThat(def.linearOrder()).containsExactly("n1", "n2", "n3");
        assertThat(def.node("n1").type()).isEqualTo("rag");
    }
}
