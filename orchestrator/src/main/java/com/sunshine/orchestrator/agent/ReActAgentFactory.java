package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.memory.MemoryProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 每次对话创建独立 ReActAgent，避免单例残留 pending tool call / 并发冲突。
 * 可选 {@link AutoContextMemory}（4.6.4）压缩单次 run 内 TOOL 上下文。
 * base system-prompt 读 {@link PromptCatalogHolder}（id={@code system-prompt}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActAgentFactory {

    private final PromptCatalogHolder catalogHolder;
    private final AgentExecutionProperties executionProperties;
    private final MemoryProperties memoryProperties;
    private final DynamicToolkitFactory dynamicToolkitFactory;
    private final ProcessingStepHookFactory stepHookFactory;

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
        MemoryProperties.AutoContext ac = memoryProperties.getAutoContext();
        boolean autoContext = ac != null && ac.isEnabled();
        log.info("[ReActAgentFactory] role={} skill={} tools={} maxIters={} autoContext={}",
                request.role(), request.skillId(), toolkit.getToolNames(), maxIters, autoContext);

        ReActAgent.Builder builder = ReActAgent.builder()
                .name(resolveAgentName(request))
                .sysPrompt(composeSystemPrompt(request))
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters);

        if (autoContext) {
            builder.memory(new AutoContextMemory(buildAutoContextConfig(ac), model))
                    .hook(new AutoContextHook())
                    .hook(stepHookFactory.forBridge(bridgeId));
        } else {
            builder.hook(stepHookFactory.forBridge(bridgeId));
        }
        return builder.build();
    }

    /** 供单测断言 memory 类型；与 create 同策略 */
    Memory createMemory(OpenAIChatModel model) {
        MemoryProperties.AutoContext ac = memoryProperties.getAutoContext();
        if (ac == null || !ac.isEnabled()) {
            return null;
        }
        return new AutoContextMemory(buildAutoContextConfig(ac), model);
    }

    public static AutoContextConfig buildAutoContextConfig(MemoryProperties.AutoContext ac) {
        return AutoContextConfig.builder()
                .largePayloadThreshold(ac.getLargePayloadThreshold())
                .maxToken(ac.getMaxToken())
                .tokenRatio(ac.getTokenRatio())
                .offloadSinglePreview(ac.getOffloadSinglePreview())
                .msgThreshold(ac.getMsgThreshold())
                .lastKeep(ac.getLastKeep())
                .minConsecutiveToolMessages(ac.getMinConsecutiveToolMessages())
                .currentRoundCompressionRatio(ac.getCurrentRoundCompressionRatio())
                .minCompressionTokenThreshold(ac.getMinCompressionTokenThreshold())
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
