package com.sunshine.orchestrator.sandbox;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxCancelExpandTest {

    @Test
    void detail_execCommand() {
        assertThat(SandboxCancelExpand.detail(SandboxIds.EXEC, Map.of("command", " sleep 1 ")))
                .isEqualTo("sleep 1");
    }

    @Test
    void detail_grepPattern() {
        assertThat(SandboxCancelExpand.detail(SandboxIds.GREP, Map.of("pattern", "foo")))
                .isEqualTo("foo");
    }

    @Test
    void detail_read_returnsNull() {
        assertThat(SandboxCancelExpand.detail(SandboxIds.READ, Map.of("path", "/x"))).isNull();
    }
}
