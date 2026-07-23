package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.DynamicToolkitFactory;
import com.sunshine.orchestrator.agent.ReActAgentFactory;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.memory.MemoryProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextHook;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** MsgHub 专家 Agent — 专用 {@link ExpertSpeakHook}，不复用主 ReAct Timeline Hook */
@Component
@RequiredArgsConstructor
public class ExpertPeerAgentFactory {

    private final DynamicToolkitFactory dynamicToolkitFactory;
    private final ToolCatalogService toolCatalogService;
    private final ReActAgentFactory reactAgentFactory;
    private final MemoryProperties memoryProperties;

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
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("Sunshine-Expert-" + request.runId())
                .sysPrompt(reactAgentFactory.composeSystemPrompt(request))
                .model(model)
                .toolkit(resolveToolkit(request))
                .maxIters(resolveMaxIters(request));
        MemoryProperties.AutoContext ac = memoryProperties.getAutoContext();
        if (ac != null && ac.isEnabled()) {
            builder.memory(new AutoContextMemory(ReActAgentFactory.buildAutoContextConfig(ac), model))
                    .hook(new AutoContextHook());
        }
        return builder.hook(new ExpertSpeakHook(bridgeId, toolCatalogService)).build();
    }

    private Toolkit resolveToolkit(AgentRunRequest request) {
        List<String> whitelist = request.toolWhitelist() != null
                ? request.toolWhitelist()
                : List.of();
        return dynamicToolkitFactory.buildForSubAgent(
                whitelist, request.tenantId(), request.skillId(), request.userId());
    }

    private int resolveMaxIters(AgentRunRequest request) {
        return reactAgentFactory.resolveMaxIters(request);
    }
}
