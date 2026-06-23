package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 每次对话创建独立 ReActAgent，避免单例残留 pending tool call / 并发冲突。
 */
@Component
@RequiredArgsConstructor
public class ReActAgentFactory {

    private final AgentPromptProperties prompts;
    private final Toolkit toolkit;
    private final DynamicToolkitFactory dynamicToolkitFactory;
    private final ProcessingStepHookFactory stepHookFactory;

    @Value("${agent.max-iters:5}")
    private int maxIters;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    public ReActAgent create(AgentRunRequest request) {
        String bridgeId = request.resolveBridgeId();
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(modelBaseUrl)
                .stream(true)
                .build();

        return ReActAgent.builder()
                .name(resolveAgentName(request))
                .sysPrompt(composeSystemPrompt(request))
                .model(model)
                .toolkit(resolveToolkit(request))
                .hook(stepHookFactory.forBridge(bridgeId))
                .maxIters(resolveMaxIters(request))
                .build();
    }

    String composeSystemPrompt(AgentRunRequest request) {
        String base = prompts.systemPromptOrEmpty();
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
        if (request.role() == AgentRole.SUB
                && request.toolWhitelist() != null
                && !request.toolWhitelist().isEmpty()) {
            return dynamicToolkitFactory.build(request.toolWhitelist());
        }
        return toolkit;
    }

    int resolveMaxIters(AgentRunRequest request) {
        return request.maxIters() > 0 ? request.maxIters() : maxIters;
    }

    private static String resolveAgentName(AgentRunRequest request) {
        if (request.role() == AgentRole.SUB) {
            return "Sunshine-SubAgent";
        }
        return "Sunshine-Assistant";
    }
}
