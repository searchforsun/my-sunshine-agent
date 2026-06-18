package com.sunshine.orchestrator.agent;

import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.Toolkit;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 工具集配置。
 * ReActAgent 由 {@link ReActAgentFactory} 按请求创建，避免单例状态污染。
 */
@Slf4j
@Configuration
@RefreshScope
public class AgentConfig {

    @Bean
    public Toolkit toolkit(DynamicToolkitFactory dynamicToolkitFactory) {
        return dynamicToolkitFactory.build();
    }
}
