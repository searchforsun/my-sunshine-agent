package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextMessageBuilder;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一 system / context 消息拼装 — 6 层叠加顺序见 phase3 SSOT §3.8。
 * ReAct 的 base-system 仍由 {@link com.sunshine.orchestrator.agent.ReActAgentFactory} 注入 AgentScope。
 * 已迁 Catalog 的层从 {@link PromptCatalogHolder} 读取（缺省空串 + warn）。
 * Skill overlay 仅 skill-manager Catalog（无 Nacos 影子兜底）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptComposer {

    private final PromptCatalogHolder catalogHolder;
    private final SkillCatalogService skillCatalogService;
    private final AgentHitlProperties hitlProperties;

    /** 直连 Gateway / DIRECT 与 workflow llm 的消息列表（含 base-system） */
    public List<Map<String, Object>> composeGatewayMessages(PromptComposeRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        appendCommonGatewayLayers(messages, request, true);
        appendGatewayTail(messages, request);
        return messages;
    }

    /**
     * ReAct AgentScope 输入（不含 base-system，由 ReActAgent.sysPrompt 承载）。
     * AS 2.0 Hook 校验：PreCallEvent.inputMessages 禁止 SYSTEM 角色 → 本方法链路所有
     * 指令性/上下文消息统一收敛为 USER 角色（P0 热修，语义基本等价；P1/P2 再迁原生
     * systemMessage / appendSystemContent 注入）。
     */
    public List<Msg> composeReactInputs(PromptComposeRequest request) {
        List<Msg> inputs = new ArrayList<>();
        appendCommonReactLayers(inputs, request, false);
        appendReactTail(inputs, request);
        return inputs;
    }

    private void appendCommonGatewayLayers(
            List<Map<String, Object>> messages, PromptComposeRequest request, boolean includeBaseSystem) {
        AssembledContext ctx = request.context() != null ? request.context() : AssembledContext.empty();
        if (includeBaseSystem) {
            addGatewaySystem(messages, catalogText("system-prompt"));
        }
        // 用户个人规则（soul）：base-system 之后独立注入层；空不注入
        addGatewaySystem(messages, PersonalRulesSupport.wrap(request.personalRules()));
        // 场景覆盖层：根据 kind 注入专属上下文（chat / task）
        addGatewaySystem(messages, resolveSceneOverlay(request.kind()));
        addGatewaySystem(messages, resolveWorkspaceCheckoutOverlay(request.workspaceCheckout()));
        addGatewaySystem(messages, resolveModeOverlay(request.mode(), request.workflowId()));
        addGatewaySystem(messages, resolveSkillOverlay(request.skillId()));
        appendGatewayContextLayers(messages, ctx);
        addGatewaySystem(messages, catalogText("scope-prompt"));
        addGatewaySystem(messages, nodePromptOrEmpty(request.nodePrompt()));
    }

    private void appendCommonReactLayers(List<Msg> inputs, PromptComposeRequest request, boolean includeBaseSystem) {
        AssembledContext ctx = request.context() != null ? request.context() : AssembledContext.empty();
        if (includeBaseSystem) {
            addReactUser(inputs, catalogText("system-prompt"));
        }
        addReactUser(inputs, resolveModeOverlay(request.mode(), request.workflowId()));
        // 用户个人规则（soul）：mode-overlay 之后独立注入层；空不注入
        addReactUser(inputs, PersonalRulesSupport.wrap(request.personalRules()));
        addReactUser(inputs, resolveReactScenarioOverlay(request.reactPromptId()));
        addReactUser(inputs, resolveReactRestartOverlay(request));
        addReactUser(inputs, resolveHitlOverlay(request.mode()));
        addReactUser(inputs, resolveSkillOverlay(request.skillId()));
        // 场景覆盖层：根据 kind 注入专属上下文（chat / task）
        addReactUser(inputs, resolveSceneOverlay(request.kind()));
        // 工作区 checkout 目录：让 AI 明确当前工作目录，避免误用 main checkout
        addReactUser(inputs, resolveWorkspaceCheckoutOverlay(request.workspaceCheckout()));
        appendReactContextLayers(inputs, ctx);
        addReactUser(inputs, catalogText("scope-prompt"));
        addReactUser(inputs, nodePromptOrEmpty(request.nodePrompt()));
    }

    private void appendGatewayContextLayers(List<Map<String, Object>> messages, AssembledContext ctx) {
        ContextMessageBuilder.appendAll(
                messages, ctx, catalogText("context.layer-prompt"), catalogText("context.usage-rules"));
    }

    private void appendReactContextLayers(List<Msg> inputs, AssembledContext ctx) {
        List<Map<String, Object>> layers = new ArrayList<>();
        ContextMessageBuilder.appendAll(
                layers, ctx, catalogText("context.layer-prompt"), catalogText("context.usage-rules"));
        for (Map<String, Object> msg : layers) {
            String role = String.valueOf(msg.get("role"));
            String content = String.valueOf(msg.get("content"));
            MsgRole msgRole = switch (role) {
                case "assistant" -> MsgRole.ASSISTANT;
                case "user" -> MsgRole.USER;
                default -> MsgRole.USER;
            };
            inputs.add(Msg.builder().role(msgRole).textContent(content).build());
        }
    }

    private void appendGatewayTail(List<Map<String, Object>> messages, PromptComposeRequest request) {
        appendGatewayInjectedContexts(messages, request.injectedUserContexts());
        messages.add(Map.of(
                "role", "user",
                "content", ContextMessageBuilder.formatCurrentUser(
                        request.userMessage(), catalogText("context.current-user-marker"))));
        if (request.partialAssistant() != null && !request.partialAssistant().isEmpty()) {
            messages.add(Map.of("role", "assistant", "content", request.partialAssistant()));
        }
    }

    private void appendReactTail(List<Msg> inputs, PromptComposeRequest request) {
        appendReactInjectedContexts(inputs, request.injectedUserContexts());
        inputs.add(Msg.builder()
                .role(MsgRole.USER)
                .textContent(ContextMessageBuilder.formatCurrentUser(
                        request.userMessage(), catalogText("context.current-user-marker")))
                .build());
    }

    private static void appendGatewayInjectedContexts(List<Map<String, Object>> messages, List<String> contexts) {
        for (String context : contexts) {
            if (StringUtils.hasText(context)) {
                messages.add(Map.of("role", "user", "content", context.strip()));
            }
        }
    }

    private static void appendReactInjectedContexts(List<Msg> inputs, List<String> contexts) {
        for (String context : contexts) {
            if (StringUtils.hasText(context)) {
                inputs.add(Msg.builder().role(MsgRole.USER).textContent(context.strip()).build());
            }
        }
    }

    private String resolveSceneOverlay(String kind) {
        if (!StringUtils.hasText(kind)) {
            return "";
        }
        return catalogText("scene-overlay." + kind.strip());
    }

    /** 工作区 checkout 目录提示：模板在 Catalog（workspace.checkout-hint），{checkoutPath} 运行时替换 */
    private String resolveWorkspaceCheckoutOverlay(String checkoutPath) {
        if (!StringUtils.hasText(checkoutPath)) {
            return "";
        }
        String template = catalogText("workspace.checkout-hint");
        if (!StringUtils.hasText(template)) {
            return "";
        }
        return template.replace("{checkoutPath}", checkoutPath.strip());
    }

    private String resolveModeOverlay(PromptMode mode, String workflowId) {
        if (mode == null) {
            return "";
        }
        if (mode == PromptMode.REACT) {
            return ReactOverlayAssembler.assemble(catalogHolder.snapshot());
        }
        if (mode == PromptMode.WORKFLOW && StringUtils.hasText(workflowId)) {
            String specificId = "mode-overlay.workflow:" + workflowId.strip();
            String specific = catalogHolder.snapshot().text(specificId).map(String::strip).orElse("");
            if (StringUtils.hasText(specific)) {
                return specific;
            }
        }
        return catalogText("mode-overlay." + mode.overlayKey());
    }

    private String resolveHitlOverlay(PromptMode mode) {
        if (mode != PromptMode.REACT || hitlProperties == null || !hitlProperties.isEnabled()) {
            return "";
        }
        return catalogText("hitl.agent-prompt");
    }

    private String resolveReactRestartOverlay(PromptComposeRequest request) {
        if (!request.reactRestart()) {
            return "";
        }
        return catalogText("mode-overlay.react-restart");
    }

    private String resolveReactScenarioOverlay(String reactPromptId) {
        if (!StringUtils.hasText(reactPromptId)) {
            return "";
        }
        String id = reactPromptId.strip();
        var entry = catalogHolder.snapshot().entry(id);
        if (entry.isEmpty()) {
            log.warn("[PromptComposer] react scenario missing id={}", id);
            return "";
        }
        PromptCatalogEntry e = entry.get();
        String kind = e.kind();
        if (!e.enabled() || !isScenarioOverlayKind(kind)) {
            log.warn("[PromptComposer] react scenario invalid id={} kind={} enabled={}", id, kind, e.enabled());
            return "";
        }
        return e.contentText() != null ? e.contentText().strip() : "";
    }

    /** react-prompt 场景 overlay；planner.harness 的 kind=planner 同样走此层。 */
    private static boolean isScenarioOverlayKind(String kind) {
        return "react-prompt".equals(kind) || "planner".equals(kind);
    }

    private String resolveSkillOverlay(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return "";
        }
        String fromCatalog = skillCatalogService.overlayOrEmpty(skillId);
        return StringUtils.hasText(fromCatalog) ? fromCatalog.strip() : "";
    }

    /** Catalog 缺 id → 空串 + warn；有条目即使正文为空也不 warn */
    private String catalogText(String id) {
        return catalogHolder.snapshot().entry(id)
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElseGet(() -> {
                    log.warn("[PromptComposer] catalog missing id={}", id);
                    return "";
                });
    }

    private static String nodePromptOrEmpty(String nodePrompt) {
        return nodePrompt != null ? nodePrompt.strip() : "";
    }

    private static void addGatewaySystem(List<Map<String, Object>> messages, String text) {
        if (StringUtils.hasText(text)) {
            messages.add(Map.of("role", "system", "content", text.strip()));
        }
    }

    private static void addReactUser(List<Msg> inputs, String text) {
        if (StringUtils.hasText(text)) {
            inputs.add(Msg.builder().role(MsgRole.USER).textContent(text.strip()).build());
        }
    }
}
