package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope ReActAgent 配置
 * 使用 OpenAIChatModel 指向自建 LLM Gateway（OpenAI 兼容接口）
 * <p>
 * {@link RefreshScope} 支持 Nacos 配置热更新 System Prompt。
 * 对话历史由 {@link SunshineAgent#buildInputs} 从 DB 注入，ReActAgent 不使用全局 Memory。
 */
@Slf4j
@Configuration
@RefreshScope
@RequiredArgsConstructor
public class AgentConfig {

    private final AgentPromptProperties prompts;

    @Value("${agent.max-iters:5}")
    private int maxIters;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    @Bean
    public ReActAgent sunshineReActAgent(Toolkit toolkit, ProcessingStepHook stepHook) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(modelBaseUrl)
                .stream(true)
                .build();

        log.info("[Orchestrator] 创建 ReActAgent: model={}, baseUrl={}, maxIters={}, systemPrompt={}",
                modelName, modelBaseUrl, maxIters,
                prompts.hasSystemPrompt()
                        ? prompts.systemPromptOrEmpty().substring(0, Math.min(40, prompts.systemPromptOrEmpty().length())) + "..."
                        : "(empty — configure agent.system-prompt in Nacos)");

        return ReActAgent.builder()
                .name("Sunshine-Assistant")
                .sysPrompt(prompts.systemPromptOrEmpty())
                .model(model)
                .toolkit(toolkit)
                .hook(stepHook)
                .maxIters(maxIters)
                .build();
    }

    @Bean
    public Toolkit toolkit(DynamicToolkitFactory dynamicToolkitFactory) {
        return dynamicToolkitFactory.build();
    }
}
