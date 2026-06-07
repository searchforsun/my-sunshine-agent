package com.sunshine.orchestrator.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
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
 * Memory 独立 Bean（不加 RefreshScope）保证热更新时不丢失对话历史。
 */
@Slf4j
@Configuration
@RefreshScope
public class AgentConfig {

    @Value("${agent.system-prompt:你是一个智能助手，优先检索知识库回答用户问题。}")
    private String systemPrompt;

    @Value("${agent.max-iters:5}")
    private int maxIters;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.model.base-url:http://localhost:8300/v1}")
    private String modelBaseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    /**
     * 对话记忆 — 独立 Bean，不加 @RefreshScope
     * 保证 Nacos 配置热更新 System Prompt 时对话历史不丢失
     */
    @Bean
    public Memory agentMemory() {
        return new InMemoryMemory();
    }

    @Bean
    public ReActAgent sunshineReActAgent(Toolkit toolkit, Memory agentMemory) {
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(modelBaseUrl)
                .build();

        log.info("[Orchestrator] 创建 ReActAgent: model={}, baseUrl={}, maxIters={}, systemPrompt={}",
                modelName, modelBaseUrl, maxIters,
                systemPrompt != null && systemPrompt.length() > 40
                        ? systemPrompt.substring(0, 40) + "..."
                        : systemPrompt);

        return ReActAgent.builder()
                .name("Sunshine-Assistant")
                .sysPrompt(systemPrompt)
                .model(model)
                .memory(agentMemory)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .build();
    }

    @Bean
    public Toolkit toolkit(RagTool ragTool) {
        Toolkit tk = new Toolkit();
        tk.registerTool(ragTool);
        log.info("[Orchestrator] Toolkit 已注册工具: search_knowledge");
        return tk;
    }
}
