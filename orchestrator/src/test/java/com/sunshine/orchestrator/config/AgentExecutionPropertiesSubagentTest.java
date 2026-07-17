package com.sunshine.orchestrator.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionPropertiesSubagentTest {

    @Test
    void reactSubagent_defaults() {
        AgentExecutionProperties.React.Subagent sub = new AgentExecutionProperties.React.Subagent();
        assertThat(sub.isEnabled()).isTrue();
        assertThat(sub.getMaxIters()).isEqualTo(8);
        assertThat(sub.getTimeoutMs()).isEqualTo(180_000L);
    }

    @Test
    void react_containsSubagentInstance() {
        AgentExecutionProperties.React react = new AgentExecutionProperties.React();
        assertThat(react.getSubagent()).isNotNull();
        assertThat(react.getSubagent().isEnabled()).isTrue();
    }
}
