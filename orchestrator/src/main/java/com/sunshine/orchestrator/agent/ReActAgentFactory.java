package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 每次对话创建独立 ReActAgent，避免单例残留 pending tool call / 并发冲突。
 * 2.0 起 `.memory()` 被 `.stateStore(AgentStateStore)` 取代；
 * AutoContextMemory/AutoContextHook/AutoContextConfig 在 2.0 已整体移除，压缩改在 P2 用原生
 * CompactionConfig 重做（阈值字段保留于 {@code MemoryProperties.AutoContext} 供 P2 对标）。
 * base system-prompt 读 {@link PromptCatalogHolder}（id={@code system-prompt}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActAgentFactory {

    private final PromptCatalogHolder catalogHolder;
    private final AgentExecutionProperties executionProperties;
    private final DynamicToolkitFactory dynamicToolkitFactory;
    private final ProcessingStepMiddlewareFactory middlewareFactory;
    private final AgentStateStore stateStore;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    public ReActAgent create(AgentRunRequest request) {
        String bridgeId = request.resolveBridgeId();
        Toolkit toolkit = resolveToolkit(request);
        int maxIters = resolveMaxIters(request);
        OpenAIChatModel model = buildModel();
        log.info("[ReActAgentFactory] role={} skill={} tools={} maxIters={}",
                request.role(), request.skillId(), toolkit.getToolNames(), maxIters);

        return ReActAgent.builder()
                .name(resolveAgentName(request))
                .sysPrompt(composeSystemPrompt(request))
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .stateStore(stateStore)   // 注入 AgentStateStore（P0 占位，P2 启用续跑语义）
                .middleware(middlewareFactory.forBridge(bridgeId))
                .build();
    }

    private OpenAIChatModel buildModel() {
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(modelBaseUrl)
                .stream(true)
                .build();
    }

    public String composeSystemPrompt(AgentRunRequest request) {
        String base = catalogHolder.snapshot().entry("system-prompt")
                .map(e -> e.contentText() != null ? e.contentText().strip() : "")
                .orElseGet(() -> {
                    log.warn("[ReActAgentFactory] catalog missing id=system-prompt");
                    return "";
                });
        String overlay = request.systemOverlay();
        if (!StringUtils.hasText(overlay)) {
            return base;
        }
        String trimmed = overlay.strip();
        if (base.isBlank()) {
            return trimmed;
        }
        return base + "\n\n" + trimmed;
    }

    Toolkit resolveToolkit(AgentRunRequest request) {
        if (request.role() == AgentRole.SUB) {
            return dynamicToolkitFactory.buildForSubAgent(
                    request.toolWhitelist(), request.tenantId(), request.skillId(), request.userId());
        }
        return dynamicToolkitFactory.build(request.tenantId(), request.skillId(), request.userId());
    }

    public int resolveMaxIters(AgentRunRequest request) {
        return request.maxIters() > 0 ? request.maxIters() : executionProperties.getReact().getMaxIters();
    }

    private static String resolveAgentName(AgentRunRequest request) {
        if (request.role() == AgentRole.SUB) {
            return "Sunshine-SubAgent";
        }
        return "Sunshine-Assistant";
    }
}
