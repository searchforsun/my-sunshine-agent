package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.PromptOverlayProperties;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.memory.MemoryMessageBuilder;
import com.sunshine.orchestrator.memory.MemoryProperties;
import com.sunshine.orchestrator.memory.stm.StmBoundaryFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一 system / memory 消息拼装 — 6 层叠加顺序见 phase3 SSOT §3.8。
 * ReAct 的 base-system 仍由 {@link com.sunshine.orchestrator.agent.ReActAgentFactory} 注入 AgentScope。
 */
@Service
@RequiredArgsConstructor
public class PromptComposer {

    private final AgentPromptProperties prompts;
    private final PromptOverlayProperties overlayProperties;
    private final MemoryProperties memoryProperties;
    private final SkillCatalogService skillCatalogService;

    /** simple-llm / workflow llm 直连 Gateway 的消息列表（含 base-system） */
    public List<Map<String, Object>> composeGatewayMessages(PromptComposeRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        appendCommonGatewayLayers(messages, request, true);
        appendGatewayTail(messages, request);
        return messages;
    }

    /** ReAct AgentScope 输入（不含 base-system，由 ReActAgent.sysPrompt 承载） */
    public List<Msg> composeReactInputs(PromptComposeRequest request) {
        List<Msg> inputs = new ArrayList<>();
        appendCommonReactLayers(inputs, request, false);
        appendReactTail(inputs, request);
        return inputs;
    }

    private void appendCommonGatewayLayers(
            List<Map<String, Object>> messages, PromptComposeRequest request, boolean includeBaseSystem) {
        MemoryContext ctx = request.memory() != null ? request.memory() : MemoryContext.empty();
        if (includeBaseSystem) {
            addGatewaySystem(messages, prompts.systemPromptOrEmpty());
        }
        addGatewaySystem(messages, resolveModeOverlay(request.mode(), request.workflowId()));
        addGatewaySystem(messages, resolveSkillOverlay(request.skillId()));
        appendGatewayMemoryLayers(messages, ctx);
        addGatewaySystem(messages, scopePromptOrEmpty());
        addGatewaySystem(messages, nodePromptOrEmpty(request.nodePrompt()));
    }

    private void appendCommonReactLayers(List<Msg> inputs, PromptComposeRequest request, boolean includeBaseSystem) {
        MemoryContext ctx = request.memory() != null ? request.memory() : MemoryContext.empty();
        if (includeBaseSystem) {
            addReactSystem(inputs, prompts.systemPromptOrEmpty());
        }
        addReactSystem(inputs, resolveModeOverlay(request.mode(), request.workflowId()));
        addReactSystem(inputs, resolveSkillOverlay(request.skillId()));
        appendReactMemoryLayers(inputs, ctx);
        addReactSystem(inputs, scopePromptOrEmpty());
        addReactSystem(inputs, nodePromptOrEmpty(request.nodePrompt()));
    }

    private void appendGatewayMemoryLayers(List<Map<String, Object>> messages, MemoryContext ctx) {
        if (memoryProperties != null && StringUtils.hasText(memoryProperties.getLayerPrompt())) {
            addGatewaySystem(messages, memoryProperties.getLayerPrompt().strip());
        }
        MemoryMessageBuilder.appendLongTermLayers(messages, ctx);
        MemoryMessageBuilder.appendStmTurns(messages, ctx, memoryProperties);
    }

    private void appendReactMemoryLayers(List<Msg> inputs, MemoryContext ctx) {
        if (memoryProperties != null && StringUtils.hasText(memoryProperties.getLayerPrompt())) {
            addReactSystem(inputs, memoryProperties.getLayerPrompt().strip());
        }
        addReactSystem(inputs, ctx.ltmSnippet());
        addReactSystem(inputs, ctx.mtmSnippet());
        appendReactStmTurns(inputs, ctx);
    }

    private void appendReactStmTurns(List<Msg> inputs, MemoryContext memory) {
        if (memory.stmTurns() == null || memory.stmTurns().isEmpty()) {
            return;
        }
        String boundary = StmBoundaryFormatter.format(memoryProperties);
        if (StringUtils.hasText(boundary)) {
            addReactSystem(inputs, boundary.strip());
        }
        for (ChatTurn turn : memory.stmTurns()) {
            if (turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            MsgRole role = "assistant".equals(turn.role()) ? MsgRole.ASSISTANT : MsgRole.USER;
            inputs.add(Msg.builder().role(role).textContent(turn.content()).build());
        }
    }

    private void appendGatewayTail(List<Map<String, Object>> messages, PromptComposeRequest request) {
        appendGatewayInjectedContexts(messages, request.injectedUserContexts());
        messages.add(Map.of(
                "role", "user",
                "content", MemoryMessageBuilder.formatCurrentUser(request.userMessage(), memoryProperties)));
        if (request.partialAssistant() != null && !request.partialAssistant().isEmpty()) {
            messages.add(Map.of("role", "assistant", "content", request.partialAssistant()));
        }
    }

    private void appendReactTail(List<Msg> inputs, PromptComposeRequest request) {
        appendReactInjectedContexts(inputs, request.injectedUserContexts());
        inputs.add(Msg.builder()
                .role(MsgRole.USER)
                .textContent(MemoryMessageBuilder.formatCurrentUser(request.userMessage(), memoryProperties))
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

    private String resolveModeOverlay(PromptMode mode, String workflowId) {
        if (overlayProperties.getModeOverlays() == null || mode == null) {
            return "";
        }
        if (mode == PromptMode.WORKFLOW && StringUtils.hasText(workflowId)) {
            String specific = overlayProperties.getModeOverlays().get("workflow:" + workflowId.strip());
            if (StringUtils.hasText(specific)) {
                return specific.strip();
            }
        }
        String text = overlayProperties.getModeOverlays().get(mode.overlayKey());
        return StringUtils.hasText(text) ? text.strip() : "";
    }

    private String resolveSkillOverlay(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return "";
        }
        String fromCatalog = skillCatalogService.overlayOrEmpty(skillId);
        if (StringUtils.hasText(fromCatalog)) {
            return fromCatalog.strip();
        }
        if (overlayProperties.getSkillOverlays() == null) {
            return "";
        }
        String text = overlayProperties.getSkillOverlays().get(skillId.strip());
        return StringUtils.hasText(text) ? text.strip() : "";
    }

    private String scopePromptOrEmpty() {
        return overlayProperties.getScopePrompt() != null
                ? overlayProperties.getScopePrompt().strip()
                : "";
    }

    private static String nodePromptOrEmpty(String nodePrompt) {
        return nodePrompt != null ? nodePrompt.strip() : "";
    }

    private static void addGatewaySystem(List<Map<String, Object>> messages, String text) {
        if (StringUtils.hasText(text)) {
            messages.add(Map.of("role", "system", "content", text.strip()));
        }
    }

    private static void addReactSystem(List<Msg> inputs, String text) {
        if (StringUtils.hasText(text)) {
            inputs.add(Msg.builder().role(MsgRole.SYSTEM).textContent(text.strip()).build());
        }
    }
}
