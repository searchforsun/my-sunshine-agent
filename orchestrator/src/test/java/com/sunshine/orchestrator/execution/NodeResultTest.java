package com.sunshine.orchestrator.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeResultTest {

    @Test
    void okTypedOutputs() {
        var outputs = Map.of("output", TypedValue.scalar("result"));
        NodeResult r = NodeResult.ok(outputs);
        assertThat(r.success()).isTrue();
        assertThat(r.safeOutputs().get("output").render()).isEqualTo("result");
    }

    @Test
    void okStringOutputsCompatible() {
        var outputs = Map.of("output", "result text", "tool", "sdk__test");
        NodeResult r = NodeResult.okString(outputs);
        assertThat(r.success()).isTrue();
        assertThat(r.safeOutputs().get("output").render()).isEqualTo("result text");
        assertThat(r.safeOutputs().get("tool").render()).isEqualTo("sdk__test");
    }

    @Test
    void failReturnsErrorOutput() {
        NodeResult r = NodeResult.fail("missing param");
        assertThat(r.success()).isFalse();
        assertThat(r.safeOutputs().get("error").render()).isEqualTo("missing param");
    }

    @Test
    void safeOutputsEmptyOnNull() {
        NodeResult r = new NodeResult(true, null, java.util.List.of(), java.util.List.of());
        assertThat(r.safeOutputs()).isEmpty();
    }
}
