package com.sunshine.orchestrator.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope ReActAgent 配置
 * 使用 OpenAIChatModel 指向自建 LLM Gateway（OpenAI 兼容接口）
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Value("${agent.system-prompt:你是一个智能助手。}")
    private String systemPrompt;

    @Value("${agent.max-iters:5}")
    private int maxIters;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    @Bean
    public ReActAgent sunshineReActAgent(Toolkit toolkit) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(modelBaseUrl)
                .build();

        log.info("[Orchestrator] 创建 ReActAgent: model={}, baseUrl={}, maxIters={}",
                modelName, modelBaseUrl, maxIters);

        return ReActAgent.builder()
                .name("Sunshine-Assistant")
                .sysPrompt(systemPrompt)
                .model(model)
                .memory(new InMemoryMemory())
                .toolkit(toolkit)
                .maxIters(maxIters)
                .build();
    }

    @Bean
    public Toolkit toolkit() {
        return new Toolkit();
    }
}
