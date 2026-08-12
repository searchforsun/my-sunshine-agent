package com.sunshine.orchestrator.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionPropertiesAsyncToolTest {
    @Test
    void asyncTool_defaults_matchSpec() {
        AgentExecutionProperties.React.AsyncTool cfg = new AgentExecutionProperties.React.AsyncTool();
        assertThat(cfg.isEnabled()).isTrue();
        assertThat(cfg.getAwaitDefaultSec()).isEqualTo(30);
        assertThat(cfg.getAwaitMaxSec()).isEqualTo(120);
        assertThat(cfg.getAwaitMaxWaits()).isEqualTo(3);
        assertThat(cfg.getExecWallTimeoutSec()).isEqualTo(600);
        assertThat(cfg.getMaxConcurrentPerMessage()).isEqualTo(3);
    }
}
