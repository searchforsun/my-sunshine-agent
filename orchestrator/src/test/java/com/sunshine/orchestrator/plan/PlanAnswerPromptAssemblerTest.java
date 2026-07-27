package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanAnswerPromptAssemblerTest {

    private static final String ANSWER_TEMPLATE = """
            用户问题：{{start.userQuery}}

            上游数据：
            {{plan.upstream}}

            请严格针对上述「用户问题」作答：
            - 仅依据上游数据，用面向用户的中文 Markdown 直接回答
            - 综合循环/检索/工具结果给出结论与依据；上游为空时说明暂无可用数据
            - 禁止输出 tool_call、函数调用、JSON 协议、内部节点 id 或原始工具报文
            - 禁止复述上游中的工具调用结构；若上游含此类内容，只提炼对用户有用的事实""";

    private PlanAnswerPromptAssembler assembler;
    private PromptCatalogHolder catalogHolder;

    @BeforeEach
    void setUp() {
        catalogHolder = new PromptCatalogHolder();
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry("answer.template", "answer", "answer.template", true, 0, 1,
                        ANSWER_TEMPLATE, null))));
        assembler = new PlanAnswerPromptAssembler(catalogHolder);
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

        String prompt = assembler.apply(plan).nodesById().get(PlanNormalizer.ANSWER_NODE_ID).params().get("prompt").toString();

        assertThat(prompt).contains("{{start.userQuery}}");
        assertThat(prompt).contains("【检索知识库】");
        assertThat(prompt).contains("{{n1.output}}");
        assertThat(prompt).contains("【查待审批】");
        assertThat(prompt).contains("{{n2.output}}");
    }

    @Test
    void usesCatalogTemplateWhenConfigured() {
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry("answer.template", "answer", "answer.template", true, 0, 1,
                        "问题：{{start.userQuery}}\n\n{{plan.upstream}}\n\n请汇总。", null))));
        assembler = new PlanAnswerPromptAssembler(catalogHolder);

        PlanJson plan = PlanNormalizer.normalize(new PlanJson("p", "r",
                List.of(new PlanNode("n1", "rag", Map.of())),
                List.of(new PlanEdge("start", "n1"))));

        String prompt = assembler.apply(plan).nodesById().get(PlanNormalizer.ANSWER_NODE_ID).params().get("prompt").toString();
        assertThat(prompt).startsWith("问题：{{start.userQuery}}");
        assertThat(prompt).endsWith("请汇总。");
        assertThat(prompt).contains("{{n1.output}}");
    }

    @Test
    void missingCatalogTemplate_writesEmptyPrompt() {
        catalogHolder.replace(PromptCatalogSnapshot.of(0L, List.of()));
        assembler = new PlanAnswerPromptAssembler(catalogHolder);

        PlanJson plan = PlanNormalizer.normalize(new PlanJson("p", "r",
                List.of(new PlanNode("n1", "rag", Map.of())),
                List.of(new PlanEdge("start", "n1"))));

        String prompt = assembler.apply(plan).nodesById().get(PlanNormalizer.ANSWER_NODE_ID).params().get("prompt").toString();
        assertThat(prompt).isEmpty();
    }

    @Test
    void overwritesExistingAnswerPrompt() {
        PlanJson plan = new PlanJson("p", "r",
                List.of(new PlanNode(PlanNormalizer.ANSWER_NODE_ID, "answer",
                        Map.of("prompt", "旧 prompt meta"))),
                List.of(new PlanEdge("start", PlanNormalizer.ANSWER_NODE_ID)));

        String prompt = assembler.apply(plan).nodesById().get(PlanNormalizer.ANSWER_NODE_ID).params().get("prompt").toString();
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
