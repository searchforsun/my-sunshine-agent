package com.sunshine.orchestrator.agent;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 热更新：AgentConfig 仍带 @RefreshScope；ReActAgent 由 Factory 按请求创建。
 */
class AgentConfigRefreshTest {

    @Test
    void agentConfig_classHasRefreshScope() {
        assertThat(AgentConfig.class.isAnnotationPresent(RefreshScope.class)).isTrue();
    }

    @Test
    void agentConfig_hasNoGlobalMemoryBean() {
        boolean hasMemoryBean = Arrays.stream(AgentConfig.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .anyMatch(m -> m.getName().equals("agentMemory"));
        assertThat(hasMemoryBean).isFalse();
    }

    @Test
    void reactAgentFactory_exists() {
        assertThat(ReActAgentFactory.class).isNotNull();
    }
}
