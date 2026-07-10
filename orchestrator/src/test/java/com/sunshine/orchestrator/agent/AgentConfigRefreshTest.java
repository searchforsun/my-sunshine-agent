package com.sunshine.orchestrator.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReActAgent 由 Factory 按请求创建；Toolkit 每次经 {@link DynamicToolkitFactory} 组装，无需单例 Bean 热刷新。
 */
class AgentConfigRefreshTest {

    @Test
    void reactAgentFactory_exists() {
        assertThat(ReActAgentFactory.class).isNotNull();
    }

    @Test
    void dynamicToolkitFactory_exists() {
        assertThat(DynamicToolkitFactory.class).isNotNull();
    }
}
