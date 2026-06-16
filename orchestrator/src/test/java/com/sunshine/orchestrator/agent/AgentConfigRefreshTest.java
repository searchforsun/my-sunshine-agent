package com.sunshine.orchestrator.agent;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos System Prompt 热更新切片验收：
 * AgentConfig 带 @RefreshScope 可重建 ReActAgent；不再使用全局 InMemoryMemory Bean。
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
    void sunshineReActAgent_doesNotInjectMemory() throws NoSuchMethodException {
        Method agent = AgentConfig.class.getDeclaredMethod(
                "sunshineReActAgent", Toolkit.class, ProcessingStepHook.class);
        assertThat(agent.isAnnotationPresent(Bean.class)).isTrue();
        assertThat(Arrays.stream(agent.getParameterTypes())
                .noneMatch(p -> p.getSimpleName().equals("Memory"))).isTrue();
    }
}
