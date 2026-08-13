package com.sunshine.orchestrator.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionModeMigrationTest {

    @Test
    void from_mapsLegacyWiresToV6() {
        assertThat(ExecutionMode.from("react")).isEqualTo(ExecutionMode.FAST);
        assertThat(ExecutionMode.from("plan-workflow")).isEqualTo(ExecutionMode.PRO);
        assertThat(ExecutionMode.from("fast")).isEqualTo(ExecutionMode.FAST);
        assertThat(ExecutionMode.from("pro")).isEqualTo(ExecutionMode.PRO);
        assertThat(ExecutionMode.from("workflow")).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(ExecutionPreference.from("auto").wireValue()).isEqualTo("fast");
        assertThat(ExecutionPreference.from("plan-workflow").wireValue()).isEqualTo("pro");
    }
}
