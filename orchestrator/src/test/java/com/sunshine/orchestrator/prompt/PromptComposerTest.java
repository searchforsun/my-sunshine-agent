package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.memory.MemoryMessageBuilder;
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

    private static final String USER_MARKER = "【当前提问 · 仅此作答】";
    private static final String STM_HEADER = "[本会话近期对话 · STM]";
    private static final String STM_PREAMBLE =
            "以下为同会话已结束轮次，仅供指代与消歧（如「这个 skill」「上述脚本」）。";

    @Mock
    private SkillCatalogService skillCatalogService;

    private PromptCatalogHolder catalogHolder;
    private AgentHitlProperties hitlProperties;
    private PromptComposer composer;

    @BeforeEach
    void setUp() {
        hitlProperties = new AgentHitlProperties();
        catalogHolder = new PromptCatalogHolder();
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, defaultEntries()));
        composer = new PromptComposer(catalogHolder, skillCatalogService, hitlProperties);
    }

    @Test
    void appendsEnabledFragmentsInSortOrder() {
        catalogHolder.replace(PromptCatalogSnapshot.of(2L, List.of(
                textEntry("mode-overlay.react", "mode-overlay", "BASE"),
                fragment("react-fragment.b", "F2", 2),
                fragment("react-fragment.a", "F1", 1))));
        assertThat(ReactOverlayAssembler.assemble(catalogHolder.snapshot()))
                .isEqualTo("BASE\nF1\nF2");

        catalogHolder.replace(PromptCatalogSnapshot.of(3L, List.of(
                textEntry("mode-overlay.react", "mode-overlay", "BASE"),
                fragment("react-fragment.b", "F2", 2),
                fragment("react-fragment.a", "F1", 1),
                textEntry("memory.layer-prompt", "memory", ""),
                textEntry("scope-prompt", "scope", ""),
                textEntry("hitl.agent-prompt", "hitl", ""))));
        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.empty(), "问", List.of()));
        assertThat(inputs.get(0).getTextContent()).isEqualTo("BASE\nF1\nF2");
    }

    @Test
    void composeGatewayMessages_matchesLegacyMemoryMessageBuilderOrder() {
        MemoryContext memory = new MemoryContext("ltm-snippet", "mtm-snippet", List.of(
                new ChatTurn("user", "上一轮问题"),
                new ChatTurn("assistant", "上一轮回答")));

        List<Map<String, Object>> expected = new ArrayList<>(
                MemoryMessageBuilder.buildPrefix("base-system", "memory-layer-prompt", memory));
        MemoryMessageBuilder.appendStmTurns(expected, memory, STM_HEADER, STM_PREAMBLE);
        expected.add(Map.of(
                "role", "user",
                "content", MemoryMessageBuilder.formatCurrentUser("新问题", USER_MARKER)));

        List<Map<String, Object>> actual = composer.composeGatewayMessages(
                PromptComposeRequest.forDirect(memory, "新问题"));

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
        assertThat(inputs.get(0).getTextContent()).isEqualTo("react-mode-minimal");
        assertThat(inputs.stream().map(Msg::getTextContent)).contains("memory-layer-prompt");
        assertThat(inputs.stream().map(Msg::getTextContent)).doesNotContain("base-system");

        Msg lastUser = inputs.get(inputs.size() - 1);
        assertThat(lastUser.getRole()).isEqualTo(MsgRole.USER);
        assertThat(lastUser.getTextContent()).contains("当前问");
        assertThat(inputs.get(inputs.size() - 2).getTextContent()).isEqualTo("rag-context");
    }

    @Test
    void composeGatewayMessages_appliesModeAndScopeOverlays() {
        replaceCatalogTexts(Map.of(
                "mode-overlay.direct", "mode-simple",
                "scope-prompt", "scope-boundary"));

        MemoryContext memory = new MemoryContext("", "", List.of(
                new ChatTurn("user", "历史问")));

        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forDirect(memory, "新问题"));

        assertThat(messages.get(0)).containsEntry("content", "base-system");
        assertThat(messages.get(1)).containsEntry("content", "mode-simple");
        assertThat(messages.stream().map(m -> m.get("content").toString()))
                .anyMatch(c -> c.contains("scope-boundary"));
    }

    @Test
    void composeGatewayMessages_continueAppendsPartialAssistant() {
        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forDirectContinue(MemoryContext.empty(), "继续", "已生成一半"));

        assertThat(messages.get(messages.size() - 1))
                .containsEntry("role", "assistant")
                .containsEntry("content", "已生成一半");
    }

    @Test
    void composeGatewayMessages_workflowLlm_includesNodePromptAsSystemLayer() {
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                textEntry("system-prompt", "system", "base-system"),
                textEntry("mode-overlay.workflow:knowledge-qa", "mode-overlay", "workflow-mode"),
                textEntry("mode-overlay.workflow", "mode-overlay", ""),
                textEntry("memory.layer-prompt", "memory", "memory-layer-prompt"),
                textEntry("memory.current-user-marker", "memory", USER_MARKER),
                textEntry("scope-prompt", "scope", ""))));
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
    void composeReactInputs_appliesReactScenarioOverlayAfterModeOverlay() {
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                textEntry("mode-overlay.react", "mode-overlay", "BASE"),
                textEntry("react-prompt.demo-scenario", "react-prompt", "SCENARIO"),
                textEntry("memory.layer-prompt", "memory", ""),
                textEntry("memory.current-user-marker", "memory", USER_MARKER),
                textEntry("scope-prompt", "scope", ""),
                textEntry("hitl.agent-prompt", "hitl", ""))));

        List<Msg> inputs = composer.composeReactInputs(
                PromptComposeRequest.forReact(
                        MemoryContext.empty(), "问", null, List.of(), false, "react-prompt.demo-scenario"));
        assertThat(inputs.get(0).getTextContent()).isEqualTo("BASE");
        assertThat(inputs.get(1).getTextContent()).isEqualTo("SCENARIO");
    }

    @Test
    void composeReactInputs_skipsBlankInjectedContexts() {
        replaceCatalogTexts(Map.of("memory.layer-prompt", "", "mode-overlay.react", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.empty(), "问", List.of("", "  ", "有效上下文")));

        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0).getTextContent()).isEqualTo("有效上下文");
    }

    @Test
    void composeReactInputs_appliesSkillOverlayFromCatalog() {
        when(skillCatalogService.overlayOrEmpty("finance-analysis")).thenReturn("catalog-skill-overlay");
        replaceCatalogTexts(Map.of("memory.layer-prompt", "", "mode-overlay.react", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.forSubAgent(), "分析待办", "finance-analysis", List.of("待办 JSON")));

        assertThat(inputs.stream().map(Msg::getTextContent))
                .anyMatch(t -> t.contains("catalog-skill-overlay"));
    }

    @Test
    void composeReactInputs_skipsSkillOverlayWhenCatalogEmpty() {
        when(skillCatalogService.overlayOrEmpty("finance-analysis")).thenReturn("");
        replaceCatalogTexts(Map.of("memory.layer-prompt", "", "mode-overlay.react", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.forSubAgent(), "分析待办", "finance-analysis", List.of("待办 JSON")));

        assertThat(inputs.stream().map(Msg::getTextContent))
                .noneMatch(t -> t != null && t.contains("skill-finance"));
        assertThat(inputs.get(inputs.size() - 2).getTextContent()).isEqualTo("待办 JSON");
    }

    @Test
    void composeReactInputs_injectsHitlAgentPromptWhenEnabled() {
        replaceCatalogTexts(Map.of(
                "memory.layer-prompt", "",
                "mode-overlay.react", "",
                "hitl.agent-prompt", "写操作须直接 tool call"));
        hitlProperties.setEnabled(true);

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.forSubAgent(), "审批 T1004", List.of()));

        assertThat(inputs.stream().map(Msg::getTextContent))
                .anyMatch(t -> t.contains("写操作须直接 tool call"));
    }

    @Test
    void composeReactInputs_skipsHitlAgentPromptWhenDisabled() {
        replaceCatalogTexts(Map.of(
                "memory.layer-prompt", "",
                "mode-overlay.react", "",
                "hitl.agent-prompt", "写操作须直接 tool call"));
        hitlProperties.setEnabled(false);

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                MemoryContext.forSubAgent(), "审批 T1004", List.of()));

        assertThat(inputs.stream().map(Msg::getTextContent))
                .noneMatch(t -> t.contains("写操作须直接 tool call"));
    }

    private void replaceCatalogTexts(Map<String, String> overrides) {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("system-prompt", "base-system");
        texts.put("mode-overlay.react", "react-mode-minimal");
        texts.put("mode-overlay.direct", "");
        texts.put("mode-overlay.workflow", "");
        texts.put("mode-overlay.react-restart", "");
        texts.put("memory.layer-prompt", "memory-layer-prompt");
        texts.put("memory.stm.header", STM_HEADER);
        texts.put("memory.stm.preamble", STM_PREAMBLE);
        texts.put("memory.current-user-marker", USER_MARKER);
        texts.put("scope-prompt", "");
        texts.put("hitl.agent-prompt", "");
        texts.putAll(overrides);
        List<PromptCatalogEntry> entries = new ArrayList<>();
        texts.forEach((id, body) -> entries.add(textEntry(id, kindFor(id), body)));
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, entries));
    }

    private List<PromptCatalogEntry> defaultEntries() {
        return List.of(
                textEntry("system-prompt", "system", "base-system"),
                textEntry("mode-overlay.react", "mode-overlay", "react-mode-minimal"),
                textEntry("mode-overlay.direct", "mode-overlay", ""),
                textEntry("mode-overlay.workflow", "mode-overlay", ""),
                textEntry("mode-overlay.react-restart", "mode-overlay", ""),
                textEntry("memory.layer-prompt", "memory", "memory-layer-prompt"),
                textEntry("memory.stm.header", "memory", STM_HEADER),
                textEntry("memory.stm.preamble", "memory", STM_PREAMBLE),
                textEntry("memory.current-user-marker", "memory", USER_MARKER),
                textEntry("scope-prompt", "scope", ""),
                textEntry("hitl.agent-prompt", "hitl", ""));
    }

    private static String kindFor(String id) {
        if (id.startsWith("mode-overlay")) return "mode-overlay";
        if (id.startsWith("memory.")) return "memory";
        if (id.startsWith("hitl.")) return "hitl";
        if (id.startsWith("react-prompt")) return "react-prompt";
        if ("scope-prompt".equals(id)) return "scope";
        return "system";
    }

    private static PromptCatalogEntry textEntry(String id, String kind, String text) {
        return new PromptCatalogEntry(id, kind, id, true, 0, 1, text, null);
    }

    private static PromptCatalogEntry fragment(String id, String text, int sortOrder) {
        return new PromptCatalogEntry(
                id, "react-fragment", id, true, 0, 1, text,
                "{\"attachTo\":\"mode-overlay.react\",\"sortOrder\":" + sortOrder + "}");
    }
}
