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
    @Mock
    private PlanAnswerPromptAssembler answerPromptAssembler;

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
        when(answerPromptAssembler.apply(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowDefinition def = materializer.materialize(plan);
        assertThat(def.id()).isEqualTo("dyn-1");
        assertThat(def.linearOrder()).containsExactly("n1", "n2", "n3");
        assertThat(def.node("n1").type()).isEqualTo("rag");
    }

    @Test
    void materializesMultiAgentLinearPlan() {
        PlanJson plan = new PlanJson("dyn-multi", "制度+财务+合规",
                List.of(
                        new PlanNode("n1", "rag", Map.of("topK", "3"), "检索制度"),
                        new PlanNode("n2", "tool", Map.of("tool", "list_finance_messages"), "查待审批"),
                        new PlanNode("n3", "agent",
                                Map.of("skill", "policy-review", "context", "{{n1.output}}", "query", "解读制度"),
                                "制度解读"),
                        new PlanNode("n4", "agent",
                                Map.of("skill", "compliance-check", "context", "{{n2.output}}", "query", "合规审查"),
                                "合规分析"),
                        new PlanNode("answer", "answer", Map.of(), "生成回答")),
                List.of(
                        new PlanEdge("start", "n1"),
                        new PlanEdge("n1", "n2"),
                        new PlanEdge("n2", "n3"),
                        new PlanEdge("n3", "n4"),
                        new PlanEdge("n4", "answer")));
        when(displayNameEnricher.enrich(any())).thenAnswer(inv -> inv.getArgument(0));
        when(answerPromptAssembler.apply(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowDefinition def = materializer.materialize(plan);
        assertThat(def.linearOrder()).containsExactly("n1", "n2", "n3", "n4", "answer");
        assertThat(def.node("n3").type()).isEqualTo("agent");
        assertThat(def.node("n4").type()).isEqualTo("agent");
        assertThat(def.node("n3").params().get("skill")).isEqualTo("policy-review");
    }
}
