package com.sunshine.orchestrator.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionPropertiesDecisionTest {
    @Test
    void decision_defaults_disabled_and_timeout300() {
        AgentExecutionProperties.React.Decision d = new AgentExecutionProperties.React.Decision();
        assertThat(d.isEnabled()).isFalse();
        assertThat(d.getTimeoutSec()).isEqualTo(300);
    }
}
