package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 每次对话创建独立 ReActAgent，避免单例残留 pending tool call / 并发冲突。
 */
@Component
@RequiredArgsConstructor
public class ReActAgentFactory {

    private final AgentPromptProperties prompts;
    private final Toolkit toolkit;
    private final ProcessingStepHook stepHook;

    @Value("${agent.max-iters:5}")
    private int maxIters;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    public ReActAgent create() {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(modelBaseUrl)
                .stream(true)
                .build();

        return ReActAgent.builder()
                .name("Sunshine-Assistant")
                .sysPrompt(prompts.systemPromptOrEmpty())
                .model(model)
                .toolkit(toolkit)
                .hook(stepHook)
                .maxIters(maxIters)
                .build();
    }
}
