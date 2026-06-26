package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.PromptOverlayProperties;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.memory.MemoryMessageBuilder;
import com.sunshine.orchestrator.memory.MemoryProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptComposerTest {

    @Mock
    private SkillCatalogService skillCatalogService;

    private AgentPromptProperties prompts;
    private MemoryProperties memoryProperties;
    private PromptOverlayProperties overlayProperties;
    private AgentHitlProperties hitlProperties;
    private PromptComposer composer;

    @BeforeEach
    void setUp() {
        prompts = new AgentPromptProperties();
        prompts.setSystemPrompt("base-system");
        memoryProperties = new MemoryProperties();
        memoryProperties.setLayerPrompt("memory-layer-prompt");
        memoryProperties.setCurrentUserMarker("【当前提问 · 仅此作答】");
        overlayProperties = new PromptOverlayProperties();
        hitlProperties = new AgentHitlProperties();
        composer = new PromptComposer(prompts, overlayProperties, memoryProperties, skillCatalogService, hitlProperties);
    }

    @Test
    void composeGatewayMessages_matchesLegacyMemoryMessageBuilderOrder() {
        MemoryContext memory = new MemoryContext("ltm-snippet", "mtm-snippet", List.of(
                new ChatTurn("user", "上一轮问题"),
                new ChatTurn("assistant", "上一轮回答")));

        List<Map<String, Object>> expected = new ArrayList<>(
                MemoryMessageBuilder.buildPrefix(prompts, memoryProperties, memory));
        MemoryMessageBuilder.appendStmTurns(expected, memory, memoryProperties);
        expected.add(Map.of(
                "role", "user",
                "content", MemoryMessageBuilder.formatCurrentUser("新问题", memoryProperties)));

        List<Map<String, Object>> actual = composer.composeGatewayMessages(
                PromptComposeRequest.forSimpleLlm(memory, "新问题"));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void composeReactInputs_excludesBaseSystemAndPreservesMemoryOrder() {
        MemoryContext memory = new MemoryContext("ltm", "mtm", List.of(
                new ChatTurn("user", "历史问"),
                new ChatTurn("assistant", "历史答")));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                memory, "当前问", List.of("rag-context")));

        assertThat(inputs).isNotEmpty();
        assertThat(inputs.get(0).getRole()).isEqualTo(MsgRole.SYSTEM);
        assertThat(inputs.get(0).getTextContent()).isEqualTo("memory-layer-prompt");
        assertThat(inputs.stream().map(Msg::getTextContent)).doesNotContain("base-system");

        Msg lastUser = inputs.get(inputs.size() - 1);
        assertThat(lastUser.getRole()).isEqualTo(MsgRole.USER);
        assertThat(lastUser.getTextContent()).contains("当前问");
        assertThat(inputs.get(inputs.size() - 2).getTextContent()).isEqualTo("rag-context");
    }

    @Test
    void composeGatewayMessages_appliesModeAndScopeOverlays() {
        overlayProperties.setModeOverlays(new LinkedHashMap<>(Map.of("simple-llm", "mode-simple")));
        overlayProperties.setScopePrompt("scope-boundary");

        MemoryContext memory = new MemoryContext("", "", List.of(
                new ChatTurn("user", "历史问")));

        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forSimpleLlm(memory, "新问题"));

        assertThat(messages.get(0)).containsEntry("content", "base-system");
        assertThat(messages.get(1)).containsEntry("content", "mode-simple");
        assertThat(messages.stream().map(m -> m.get("content").toString()))
                .anyMatch(c -> c.contains("scope-boundary"));
    }

    @Test
    void composeGatewayMessages_continueAppendsPartialAssistant() {
        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forSimpleLlmContinue(MemoryContext.empty(), "继续", "已生成一半"));

        assertThat(messages.get(messages.size() - 1))
                .containsEntry("role", "assistant")
                .containsEntry("content", "已生成一半");
    }

    @Test
    void composeGatewayMessages_workflowLlm_includesNodePromptAsSystemLayer() {
        overlayProperties.setModeOverlays(new LinkedHashMap<>(Map.of("workflow:knowledge-qa", "workflow-mode")));
        String nodePrompt = "仅根据检索结果回答。\n检索：制度片段A";

        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forWorkflowLlm(
                        "knowledge-qa", MemoryContext.empty(), "年假几天", nodePrompt));

        assertThat(messages.get(0)).containsEntry("content", "base-system");
        assertThat(messages.get(1)).containsEntry("content", "workflow-mode");
        assertThat(messages.stream().map(m -> m.get("content").toString()))
                .anyMatch(c -> c.contains("制度片段A"));
        assertThat(messages.get(messages.size() - 1).get("content").toString()).contains("年假几天");
    }

    @Test
    void composeReactInputs_skipsBlankInjectedContexts() {
        memoryProperties.setLayerPrompt("");
        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.empty(), "问", List.of("", "  ", "有效上下文")));

        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0).getTextContent()).isEqualTo("有效上下文");
    }

    @Test
    void composeReactInputs_appliesSkillOverlayFromCatalog() {
        when(skillCatalogService.overlayOrEmpty("finance-analysis")).thenReturn("catalog-skill-overlay");
        memoryProperties.setLayerPrompt("");

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.forSubAgent(), "分析待办", "finance-analysis", List.of("待办 JSON")));

        assertThat(inputs.stream().map(Msg::getTextContent))
                .anyMatch(t -> t.contains("catalog-skill-overlay"));
    }

    @Test
    void composeReactInputs_appliesSkillOverlay() {
        overlayProperties.setSkillOverlays(new LinkedHashMap<>(Map.of(
                "finance-analysis", "skill-finance-overlay")));
        memoryProperties.setLayerPrompt("");
        when(skillCatalogService.overlayOrEmpty("finance-analysis")).thenReturn("");

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.forSubAgent(), "分析待办", "finance-analysis", List.of("待办 JSON")));

        assertThat(inputs.stream().map(Msg::getTextContent))
                .anyMatch(t -> t.contains("skill-finance-overlay"));
        assertThat(inputs.get(inputs.size() - 2).getTextContent()).isEqualTo("待办 JSON");
        assertThat(inputs.stream().map(Msg::getTextContent)).noneMatch(t -> t.contains("ltm"));
    }

    @Test
    void composeReactInputs_injectsHitlAgentPromptWhenEnabled() {
        memoryProperties.setLayerPrompt("");
        hitlProperties.setEnabled(true);
        hitlProperties.setAgentPrompt("写操作须直接 tool call");

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.forSubAgent(), "审批 T1004", List.of()));

        assertThat(inputs.stream().map(Msg::getTextContent))
                .anyMatch(t -> t.contains("写操作须直接 tool call"));
    }

    @Test
    void composeReactInputs_skipsHitlAgentPromptWhenDisabled() {
        memoryProperties.setLayerPrompt("");
        hitlProperties.setEnabled(false);
        hitlProperties.setAgentPrompt("写操作须直接 tool call");

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.forSubAgent(), "审批 T1004", List.of()));

        assertThat(inputs.stream().map(Msg::getTextContent))
                .noneMatch(t -> t.contains("写操作须直接 tool call"));
    }
}
