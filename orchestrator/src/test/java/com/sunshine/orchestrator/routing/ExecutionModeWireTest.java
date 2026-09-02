package com.sunshine.orchestrator.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 协议 wire 唯一取值：fast / pro / workflow；未知值视为非法 */
class ExecutionModeWireTest {

    @Test
    void from_parsesWireValues() {
        assertThat(ExecutionMode.from("fast")).isEqualTo(ExecutionMode.FAST);
        assertThat(ExecutionMode.from("pro")).isEqualTo(ExecutionMode.PRO);
        assertThat(ExecutionMode.from("workflow")).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(ExecutionMode.from("fast").wireValue()).isEqualTo("fast");
        assertThat(ExecutionMode.from("pro").wireValue()).isEqualTo("pro");
        assertThat(ExecutionMode.from("workflow").wireValue()).isEqualTo("workflow");
        assertThat(ExecutionMode.toStoredWire("pro")).isEqualTo("pro");
    }

    @Test
    void from_blankDefaultsToFast() {
        assertThat(ExecutionMode.from(null)).isEqualTo(ExecutionMode.FAST);
        assertThat(ExecutionMode.from("  ")).isEqualTo(ExecutionMode.FAST);
        assertThat(ExecutionMode.from(null)).isEqualTo(ExecutionMode.FAST);
        assertThat(ExecutionMode.toStoredWire(null)).isNull();
        assertThat(ExecutionMode.toStoredWire("  ")).isNull();
    }

    @Test
    void from_legacyAliasIsRejected() {
        assertThatThrownBy(() -> ExecutionMode.from("react")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionMode.from("auto")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionMode.from("plan-workflow")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionMode.from("plan")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionMode.from("pipeline")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionMode.toStoredWire("plan-workflow")).isInstanceOf(IllegalArgumentException.class);
    }
}
