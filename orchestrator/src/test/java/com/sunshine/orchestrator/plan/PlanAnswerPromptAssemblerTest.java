package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.config.PromptOverlayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanAnswerPromptAssemblerTest {

    private PlanAnswerPromptAssembler assembler;
    private PromptOverlayProperties props;

    @BeforeEach
    void setUp() {
        props = new PromptOverlayProperties();
        assembler = new PlanAnswerPromptAssembler(props);
    }

    @Test
    void injectsUpstreamByLinearOrder() {
        PlanJson plan = PlanNormalizer.normalize(new PlanJson("p", "r",
                List.of(
                        new PlanNode("n1", "rag", Map.of(), "检索知识库"),
                        new PlanNode("n2", "tool", Map.of("tool", "x"), "查待审批")),
                List.of(
                        new PlanEdge("start", "n1"),
                        new PlanEdge("n1", "n2"))));

        String prompt = assembler.apply(plan).nodesById().get(PlanNormalizer.ANSWER_NODE_ID).params().get("prompt");

        assertThat(prompt).contains("{{start.userQuery}}");
        assertThat(prompt).contains("【检索知识库】");
        assertThat(prompt).contains("{{n1.output}}");
        assertThat(prompt).contains("【查待审批】");
        assertThat(prompt).contains("{{n2.output}}");
    }

    @Test
    void usesNacosTemplateWhenConfigured() {
        props.setAnswerTemplate("问题：{{start.userQuery}}\n\n{{plan.upstream}}\n\n请汇总。");
        assembler = new PlanAnswerPromptAssembler(props);

        PlanJson plan = PlanNormalizer.normalize(new PlanJson("p", "r",
                List.of(new PlanNode("n1", "rag", Map.of())),
                List.of(new PlanEdge("start", "n1"))));

        String prompt = assembler.apply(plan).nodesById().get(PlanNormalizer.ANSWER_NODE_ID).params().get("prompt");
        assertThat(prompt).startsWith("问题：{{start.userQuery}}");
        assertThat(prompt).endsWith("请汇总。");
        assertThat(prompt).contains("{{n1.output}}");
    }

    @Test
    void overwritesExistingAnswerPrompt() {
        PlanJson plan = new PlanJson("p", "r",
                List.of(new PlanNode(PlanNormalizer.ANSWER_NODE_ID, "answer",
                        Map.of("prompt", "旧 prompt meta"))),
                List.of(new PlanEdge("start", PlanNormalizer.ANSWER_NODE_ID)));

        String prompt = assembler.apply(plan).nodesById().get(PlanNormalizer.ANSWER_NODE_ID).params().get("prompt");
        assertThat(prompt).doesNotContain("旧 prompt");
        assertThat(prompt).contains("{{start.userQuery}}");
    }

    @Test
    void noAnswerNodeReturnsUnchanged() {
        PlanJson plan = new PlanJson("p", "r",
                List.of(new PlanNode("n1", "rag", Map.of())),
                List.of(new PlanEdge("start", "n1")));
        assertThat(assembler.apply(plan)).isSameAs(plan);
    }
}
