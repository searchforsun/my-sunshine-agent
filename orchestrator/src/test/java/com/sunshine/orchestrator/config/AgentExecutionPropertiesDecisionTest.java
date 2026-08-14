package com.sunshine.orchestrator.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionPropertiesDecisionTest {
    @Test
    void decision_defaults_disabled_and_infiniteWait() {
        AgentExecutionProperties.React.Decision d = new AgentExecutionProperties.React.Decision();
        assertThat(d.isEnabled()).isFalse();
        // timeoutSec<=0 = 无限等待（仅用户作答/跳过/停止结束）
        assertThat(d.getTimeoutSec()).isEqualTo(0);
    }
}
