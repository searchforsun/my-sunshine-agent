package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextGroupEstimator;
import com.sunshine.orchestrator.context.ContextMessageBuilder;
import com.sunshine.orchestrator.context.TokenEstimator;
import com.sunshine.orchestrator.conversation.ChatTurn;
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
    private static final String LAYER_PROMPT = "context-layer-prompt";
    private static final String USAGE_RULES = "仅供指代";

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
        composer = new PromptComposer(catalogHolder, skillCatalogService, hitlProperties,
                new ContextGroupEstimator(new TokenEstimator()));
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
                textEntry("context.layer-prompt", "context", ""),
                textEntry("context.usage-rules", "context", ""),
                textEntry("scope-prompt", "scope", ""),
                textEntry("hitl.agent-prompt", "hitl", ""))));
        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", List.of()), "").inputs();
        assertThat(inputs.get(0).getTextContent()).isEqualTo("BASE\nF1\nF2");
    }

    @Test
    void composeReactInputs_recordsStaticGroupTokens() {
        // 既有 fixture：system-prompt / scope-prompt / mode-overlay.react 等 entry 已注入
        ComposedReactInputs composed = composer.composeReactInputs(
                PromptComposeRequest.forReact(AssembledContext.empty(), "问题", List.of()),
                "BASE_SYSTEM");

        assertThat(composed.inputs()).isNotEmpty();
        Map<String, Integer> groups = composed.staticGroups();
        assertThat(groups.get("system")).isPositive();
        assertThat(groups).containsKeys("rules", "skills", "contextLayers");
    }

    @Test
    void composeGatewayMessages_ordersContextLayersViaBuilder() {
        AssembledContext ctx = new AssembledContext(
                "[用户状态 · L2]\n- preference: 简洁",
                "[更早对话 · Far]\n曾讨论差旅",
                List.of(new ChatTurn("user", "Q1"), new ChatTurn("assistant", "A1摘要")),
                List.of(new ChatTurn("user", "Q2"), new ChatTurn("assistant", "A2全文")),
                "[历史材料 · L3 · 可能过期]\n- …");

        List<Map<String, Object>> expected = new ArrayList<>();
        expected.add(Map.of("role", "system", "content", "base-system"));
        ContextMessageBuilder.appendAll(expected, ctx, LAYER_PROMPT, USAGE_RULES);
        expected.add(Map.of(
                "role", "user",
                "content", ContextMessageBuilder.formatCurrentUser("新问题", USER_MARKER)));

        List<Map<String, Object>> actual = composer.composeGatewayMessages(
                PromptComposeRequest.forWorkflowLlm("knowledge-qa", ctx, "新问题", null));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void composeReactInputs_excludesBaseSystemAndPreservesContextOrder() {
        AssembledContext ctx = new AssembledContext(
                "l2-block", "far-block", List.of(),
                List.of(new ChatTurn("user", "历史问"), new ChatTurn("assistant", "历史答")),
                "");

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                ctx, "当前问", List.of("rag-context")), "").inputs();

        assertThat(inputs).isNotEmpty();
        // AS 2.0 PreCall hook guard：inputMessages 禁止 SYSTEM 角色
        assertThat(inputs.get(0).getRole()).isEqualTo(MsgRole.USER);
        assertThat(inputs.get(0).getTextContent()).isEqualTo("react-mode-minimal");
        assertThat(inputs.stream().map(Msg::getTextContent)).anyMatch(t -> t.contains(LAYER_PROMPT));
        assertThat(inputs.stream().map(Msg::getTextContent)).anyMatch(t -> t.contains("l2-block"));
        assertThat(inputs.stream().map(Msg::getTextContent)).doesNotContain("base-system");

        Msg lastUser = inputs.get(inputs.size() - 1);
        assertThat(lastUser.getRole()).isEqualTo(MsgRole.USER);
        assertThat(lastUser.getTextContent()).contains("当前问");
        assertThat(inputs.get(inputs.size() - 2).getTextContent()).isEqualTo("rag-context");
    }

    @Test
    void composeReactInputs_neverEmitsSystemRole_messagesPreserveTextAndNonSystemRoles() {
        // 覆盖完整 react 链路：mode overlay + restart overlay + hitl overlay
        // + skill overlay + 上下文 L2/Far/Mid/Near/L3 + scope + nodePrompt + injected + 当前提问
        when(skillCatalogService.overlayOrEmpty("finance-analysis")).thenReturn("skill-overlay-text");
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                textEntry("system-prompt", "system", "base-system"),
                textEntry("mode-overlay.react", "mode-overlay", "react-mode-overlay"),
                textEntry("mode-overlay.react-restart", "mode-overlay", "react-restart-overlay"),
                textEntry("hitl.agent-prompt", "hitl", "hitl-overlay"),
                textEntry("context.layer-prompt", "context", LAYER_PROMPT),
                textEntry("context.usage-rules", "context", USAGE_RULES),
                textEntry("context.current-user-marker", "context", USER_MARKER),
                textEntry("scope-prompt", "scope", "scope-text"))));
        hitlProperties.setEnabled(true);

        AssembledContext ctx = new AssembledContext(
                "l2-block", "far-block",
                List.of(new ChatTurn("user", "mid-Q"), new ChatTurn("assistant", "mid-A")),
                List.of(new ChatTurn("user", "near-Q"), new ChatTurn("assistant", "near-A")),
                "l3-block");

        List<Msg> inputs = composer.composeReactInputs(new PromptComposeRequest(
                PromptMode.REACT, ctx, "当前提问正文", null, "finance-analysis", "node-prompt-text",
                List.of("injected-ctx"), null, true, null, null, null, null, null, null, null), "").inputs();

        // 主断言：无任何 SYSTEM 角色
        assertThat(inputs).isNotEmpty();
        assertThat(inputs).allMatch(m -> m.getRole() != MsgRole.SYSTEM,
                "AS 2.0 PreCall hook guard: inputMessages 禁止 SYSTEM 角色");

        // 指令/上下文文本不丢（角色已收敛为 USER，但正文一字未改）
        List<String> texts = inputs.stream().map(Msg::getTextContent).toList();
        assertThat(texts).anyMatch(t -> t.contains("react-mode-overlay"));
        assertThat(texts).anyMatch(t -> t.contains("react-restart-overlay"));
        assertThat(texts).anyMatch(t -> t.contains("hitl-overlay"));
        assertThat(texts).anyMatch(t -> t.contains("skill-overlay-text"));
        assertThat(texts).anyMatch(t -> t.contains(LAYER_PROMPT));
        assertThat(texts).anyMatch(t -> t.contains("l2-block"));
        assertThat(texts).anyMatch(t -> t.contains("far-block"));
        assertThat(texts).anyMatch(t -> t.contains("mid-Q"));
        assertThat(texts).anyMatch(t -> t.contains("mid-A"));
        assertThat(texts).anyMatch(t -> t.contains("near-Q"));
        assertThat(texts).anyMatch(t -> t.contains("near-A"));
        assertThat(texts).anyMatch(t -> t.contains("l3-block"));
        assertThat(texts).anyMatch(t -> t.contains("scope-text"));
        assertThat(texts).anyMatch(t -> t.contains("node-prompt-text"));
        assertThat(texts).anyMatch(t -> t.contains("injected-ctx"));
        // 末尾仍是当前提问 user 消息
        Msg last = inputs.get(inputs.size() - 1);
        assertThat(last.getRole()).isEqualTo(MsgRole.USER);
        assertThat(last.getTextContent()).contains("当前提问正文");
        // user/assistant 历史对话角色保持
        assertThat(inputs.stream().filter(m -> "mid-A".equals(m.getTextContent())).findFirst())
                .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(MsgRole.ASSISTANT));
        assertThat(inputs.stream().filter(m -> "mid-Q".equals(m.getTextContent())).findFirst())
                .hasValueSatisfying(m -> assertThat(m.getRole()).isEqualTo(MsgRole.USER));
    }

    @Test
    void composeGatewayMessages_appliesModeAndScopeOverlays() {
        replaceCatalogTexts(Map.of(
                "mode-overlay.workflow", "mode-simple",
                "scope-prompt", "scope-boundary"));

        AssembledContext ctx = new AssembledContext(
                "", "", List.of(), List.of(new ChatTurn("user", "历史问")), "");

        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forWorkflowLlm("knowledge-qa", ctx, "新问题", null));

        assertThat(messages.get(0)).containsEntry("content", "base-system");
        assertThat(messages.get(1)).containsEntry("content", "mode-simple");
        assertThat(messages.stream().map(m -> m.get("content").toString()))
                .anyMatch(c -> c.contains("scope-boundary"));
    }

    @Test
    void composeGatewayMessages_workflowLlm_includesNodePromptAsSystemLayer() {
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                textEntry("system-prompt", "system", "base-system"),
                textEntry("mode-overlay.workflow:knowledge-qa", "mode-overlay", "workflow-mode"),
                textEntry("mode-overlay.workflow", "mode-overlay", ""),
                textEntry("context.layer-prompt", "context", LAYER_PROMPT),
                textEntry("context.usage-rules", "context", USAGE_RULES),
                textEntry("context.current-user-marker", "context", USER_MARKER),
                textEntry("scope-prompt", "scope", ""))));
        String nodePrompt = "仅根据检索结果回答。\n检索：制度片段A";

        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forWorkflowLlm(
                        "knowledge-qa", AssembledContext.empty(), "年假几天", nodePrompt));

        assertThat(messages.get(0)).containsEntry("content", "base-system");
        assertThat(messages.get(1)).containsEntry("content", "workflow-mode");
        assertThat(messages.stream().map(m -> m.get("content").toString()))
                .anyMatch(c -> c.contains("制度片段A"));
        assertThat(messages.get(messages.size() - 1).get("content").toString()).contains("年假几天");
    }

    @Test
    void composeReactInputs_loadsPlannerHarnessKindPlanner() {
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                textEntry("mode-overlay.react", "mode-overlay", "BASE"),
                textEntry("planner.harness", "planner", "PLANNER-HARNESS"),
                textEntry("context.layer-prompt", "context", ""),
                textEntry("context.usage-rules", "context", ""),
                textEntry("context.current-user-marker", "context", USER_MARKER),
                textEntry("scope-prompt", "scope", ""),
                textEntry("hitl.agent-prompt", "hitl", ""))));

        List<Msg> inputs = composer.composeReactInputs(
                PromptComposeRequest.forPlannerHarness(
                        AssembledContext.empty(), "问", List.of(), false, "planner.harness", null, null),
                "").inputs();
        assertThat(inputs.stream().map(Msg::getTextContent)).contains("PLANNER-HARNESS");
    }

    @Test
    void composeReactInputs_skipsBlankInjectedContexts() {
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", List.of("", "  ", "有效上下文")), "").inputs();

        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(0).getTextContent()).isEqualTo("有效上下文");
    }

    @Test
    void composeReactInputs_appliesSkillOverlayFromCatalog() {
        when(skillCatalogService.overlayOrEmpty("finance-analysis")).thenReturn("catalog-skill-overlay");
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.forSubAgent(), "分析待办", "finance-analysis", List.of("待办 JSON")), "").inputs();

        assertThat(inputs.stream().map(Msg::getTextContent))
                .anyMatch(t -> t.contains("catalog-skill-overlay"));
    }

    @Test
    void composeReactInputs_mainSkipsUserSkillEnvelope() {
        // MAIN 的 skill 正文已由 SkillInjectionMiddleware 走 onSystemPrompt（SYSTEM 权威层）注入，
        // composeReactInputs 不得再以 USER 信封重复注入（重复会稀释指令权重）。
        when(skillCatalogService.overlayOrEmpty("finance-analysis")).thenReturn("HARD-GATE 正文");
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", null, List.of(), false, null, "chat", null,
                List.of("finance-analysis")), "").inputs();

        List<String> texts = inputs.stream().map(Msg::getTextContent).filter(t -> t != null).toList();
        // MAIN 不注入 USER 信封（改走 SYSTEM 通道）
        assertThat(texts).noneMatch(t -> t.contains("<skill_information>"));
        assertThat(texts).noneMatch(t -> t.contains("HARD-GATE 正文"));
    }

    @Test
    void composeReactInputs_overlaysEachTriggeredSkill() {
        // S-T：triggered 集多值化——每个已触发 skill 全文 overlay 都注入，不再只取第一个
        when(skillCatalogService.overlayOrEmpty("skill-a")).thenReturn("OVERLAY-A");
        when(skillCatalogService.overlayOrEmpty("skill-b")).thenReturn("OVERLAY-B");
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", null, List.of(), false, null, "chat", null,
                List.of("skill-a", "skill-b")), "").inputs();

        List<String> texts = inputs.stream().map(Msg::getTextContent).filter(t -> t != null).toList();
        // 多 trigger 集正文拼接已移至 SkillInjectionMiddleware（SYSTEM 权威层），composeReactInputs 不重复 USER 注入
        assertThat(texts).noneMatch(t -> t.contains("OVERLAY-A"));
        assertThat(texts).noneMatch(t -> t.contains("OVERLAY-B"));
    }

    @Test
    void composeReactInputs_injectsSkillDirectory_neverFullOverlayForUntriggered() {
        // S-D：可发现层只注入名+描述目录，不灌未触发 skill 全文
        when(skillCatalogService.overlayOrEmpty("skill-a")).thenReturn("OVERLAY-A");
        when(skillCatalogService.renderDiscoverableForPrompt("chat", List.of("skill-a"), List.of(), 20, "default"))
                .thenReturn("- **policy-qa** 制度问答 — 制度查询");
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", "",
                "context.skill-directory", "## 可用技能目录\n{skills}\n- 未加载前禁止臆造技能正文。"));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", null, List.of(), false, null, "chat", null,
                List.of("skill-a")), "").inputs();

        List<String> texts = inputs.stream().map(Msg::getTextContent).filter(t -> t != null).toList();
        assertThat(texts).anyMatch(t -> t.contains("## 可用技能目录"));
        assertThat(texts).anyMatch(t -> t.contains("policy-qa"));
        // 未触发 skill 不得出现全文；触发集正文（OVERLAY-A）改走 SYSTEM 通道，不在 USER 目录层
        assertThat(texts).noneMatch(t -> t.contains("policy-qa") && t.contains("OVERLAY"));
        assertThat(texts).noneMatch(t -> t.contains("OVERLAY-A"));
    }

    @Test
    void composeReactInputs_skipsSkillDirectoryWhenTemplateEmpty() {
        when(skillCatalogService.overlayOrEmpty("skill-a")).thenReturn("OVERLAY-A");
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", "",
                "context.skill-directory", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", null, List.of(), false, null, "chat", null,
                List.of("skill-a")), "").inputs();

        assertThat(inputs.stream().map(Msg::getTextContent).filter(t -> t != null))
                .noneMatch(t -> t.contains("policy-qa"));
    }

    @Test
    void composeReactInputs_skipsSkillOverlayWhenCatalogEmpty() {
        when(skillCatalogService.overlayOrEmpty("finance-analysis")).thenReturn("");
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", ""));

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.forSubAgent(), "分析待办", "finance-analysis", List.of("待办 JSON")), "").inputs();

        assertThat(inputs.stream().map(Msg::getTextContent))
                .noneMatch(t -> t != null && t.contains("skill-finance"));
        assertThat(inputs.get(inputs.size() - 2).getTextContent()).isEqualTo("待办 JSON");
    }

    @Test
    void composeReactInputs_injectsHitlAgentPromptWhenEnabled() {
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", "",
                "hitl.agent-prompt", "写操作须直接 tool call"));
        hitlProperties.setEnabled(true);

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.forSubAgent(), "审批 T1004", List.of()), "").inputs();

        assertThat(inputs.stream().map(Msg::getTextContent))
                .anyMatch(t -> t.contains("写操作须直接 tool call"));
    }

    @Test
    void composeReactInputs_skipsHitlAgentPromptWhenDisabled() {
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", "",
                "hitl.agent-prompt", "写操作须直接 tool call"));
        hitlProperties.setEnabled(false);

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.forSubAgent(), "审批 T1004", List.of()), "").inputs();

        assertThat(inputs.stream().map(Msg::getTextContent))
                .noneMatch(t -> t.contains("写操作须直接 tool call"));
    }

    @Test
    void composeGatewayMessages_injectsPersonalRulesAfterBaseSystem() {
        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forWorkflowLlm("knowledge-qa", AssembledContext.empty(), "新问题", null, "用文言文回答"));

        assertThat(messages.get(0)).containsEntry("content", "base-system");
        assertThat(messages.get(1))
                .containsEntry("role", "system")
                .containsEntry("content", "## 用户个人规则\n用文言文回答");
    }

    @Test
    void composeReactInputs_injectsPersonalRulesAfterModeOverlay() {
        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", null, List.of(), false, "用文言文回答"), "").inputs();

        assertThat(inputs.get(0).getTextContent()).isEqualTo("react-mode-minimal");
        assertThat(inputs.get(1).getTextContent()).isEqualTo("## 用户个人规则\n用文言文回答");
        assertThat(inputs.get(1).getRole()).isEqualTo(MsgRole.USER);
    }

    @Test
    void composeReactInputs_injectsWorkspaceCheckoutHintWithReplacement() {
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", "",
                "scene-overlay.task", "task-overlay",
                "workspace.checkout-hint", "当前工作目录 {checkoutPath}"));
        hitlProperties.setEnabled(false);

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "改代码", null, List.of(), false, null, "task",
                "/workspace/branches/wt-abc123"), "").inputs();

        List<String> texts = inputs.stream().map(Msg::getTextContent).filter(t -> t != null).toList();
        assertThat(texts).anyMatch(t -> t.contains("task-overlay"));
        assertThat(texts).anyMatch(t -> t.equals("当前工作目录 /workspace/branches/wt-abc123"));
        // 占位符必须被替换，不得残留
        assertThat(texts).noneMatch(t -> t.contains("{checkoutPath}"));
    }

    @Test
    void composeReactInputs_skipsWorkspaceCheckoutHintWhenAbsent() {
        replaceCatalogTexts(Map.of(
                "context.layer-prompt", "",
                "context.usage-rules", "",
                "mode-overlay.react", "",
                "workspace.checkout-hint", "当前工作目录 {checkoutPath}"));
        hitlProperties.setEnabled(false);

        List<Msg> inputs = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", List.of()), "").inputs();

        assertThat(inputs.stream().map(Msg::getTextContent).filter(t -> t != null))
                .noneMatch(t -> t.contains("当前工作目录"));
    }

    @Test
    void compose_blankPersonalRulesNotInjected() {
        List<Map<String, Object>> gateway = composer.composeGatewayMessages(
                PromptComposeRequest.forWorkflowLlm("knowledge-qa", AssembledContext.empty(), "问", null, "   "));
        assertThat(gateway.stream().map(m -> m.get("content").toString()))
                .noneMatch(c -> c.contains("用户个人规则"));

        List<Msg> react = composer.composeReactInputs(PromptComposeRequest.forReact(
                AssembledContext.empty(), "问", null, List.of(), false, null, null), "").inputs();
        assertThat(react.stream().map(Msg::getTextContent))
                .noneMatch(t -> t != null && t.contains("用户个人规则"));
    }

    @Test
    void compose_oversizedPersonalRulesTruncated() {
        String oversized = "规".repeat(PersonalRulesSupport.MAX_LENGTH + 100);
        List<Map<String, Object>> messages = composer.composeGatewayMessages(
                PromptComposeRequest.forWorkflowLlm("knowledge-qa", AssembledContext.empty(), "问", null, oversized));

        String injected = messages.get(1).get("content").toString();
        assertThat(injected).startsWith("## 用户个人规则\n");
        assertThat(injected).hasSize("## 用户个人规则\n".length() + PersonalRulesSupport.MAX_LENGTH);
    }

    private void replaceCatalogTexts(Map<String, String> overrides) {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("system-prompt", "base-system");
        texts.put("mode-overlay.react", "react-mode-minimal");
        texts.put("mode-overlay.workflow", "");
        texts.put("mode-overlay.react-restart", "");
        texts.put("context.layer-prompt", LAYER_PROMPT);
        texts.put("context.usage-rules", USAGE_RULES);
        texts.put("context.current-user-marker", USER_MARKER);
        texts.put("context.skill-directory", "");
        texts.put("scope-prompt", "");
        texts.put("hitl.agent-prompt", "");
        texts.put("workspace.checkout-hint", "");
        texts.putAll(overrides);
        List<PromptCatalogEntry> entries = new ArrayList<>();
        texts.forEach((id, body) -> entries.add(textEntry(id, kindFor(id), body)));
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, entries));
    }

    private List<PromptCatalogEntry> defaultEntries() {
        return List.of(
                textEntry("system-prompt", "system", "base-system"),
                textEntry("mode-overlay.react", "mode-overlay", "react-mode-minimal"),
                textEntry("mode-overlay.workflow", "mode-overlay", ""),
                textEntry("mode-overlay.react-restart", "mode-overlay", ""),
                textEntry("context.layer-prompt", "context", LAYER_PROMPT),
                textEntry("context.usage-rules", "context", USAGE_RULES),
                textEntry("context.current-user-marker", "context", USER_MARKER),
                textEntry("context.skill-directory", "context", ""),
                textEntry("scope-prompt", "scope", ""),
                textEntry("hitl.agent-prompt", "hitl", ""),
                textEntry("workspace.checkout-hint", "scene-overlay", ""));
    }

    private static String kindFor(String id) {
        if (id.startsWith("mode-overlay")) return "mode-overlay";
        if (id.startsWith("context.")) return "context";
        if (id.startsWith("hitl.")) return "hitl";
        if (id.startsWith("planner.")) return "planner";
        if (id.startsWith("workspace.")) return "scene-overlay";
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
