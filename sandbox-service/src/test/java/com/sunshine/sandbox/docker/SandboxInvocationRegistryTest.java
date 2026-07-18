package com.sunshine.sandbox.docker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxInvocationRegistryTest {

    @Test
    void cancel_rejectsOtherSession() {
        SandboxInvocationRegistry registry = new SandboxInvocationRegistry();
        registry.bindFlag("sess-a", "inv-1");
        assertThat(registry.cancel("sess-b", "inv-1")).isFalse();
        assertThat(registry.isCancelled("inv-1")).isFalse();
        assertThat(registry.cancel("sess-a", "inv-1")).isTrue();
        assertThat(registry.isCancelled("inv-1")).isTrue();
    }

    @Test
    void cancel_beforeBind_scopesSession() {
        SandboxInvocationRegistry registry = new SandboxInvocationRegistry();
        assertThat(registry.cancel("sess-a", "inv-2")).isTrue();
        assertThat(registry.bindFlag("sess-b", "inv-2").get()).isFalse();
        assertThat(registry.bindFlag("sess-a", "inv-2").get()).isTrue();
    }
}
