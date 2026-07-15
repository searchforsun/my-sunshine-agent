package com.sunshine.sandbox.metrics;

import com.sunshine.sandbox.api.SandboxPolicyDto;
import com.sunshine.sandbox.session.SandboxSession;
import com.sunshine.sandbox.session.SandboxSessionStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxMetricsTest {

    @TempDir
    Path temp;

    @Test
    void sessionGaugeTracksStoreSize() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxSessionStore store = new SandboxSessionStore(registry);
        assertThat(registry.get("sandbox.session.active").gauge().value()).isEqualTo(0.0);
        store.put(new SandboxSession(
                "s1", "c1", temp,
                new SandboxPolicyDto("docker", "img", 30, 256, 0.5, List.of(), List.of())));
        assertThat(registry.get("sandbox.session.active").gauge().value()).isEqualTo(1.0);
        store.remove("s1");
        assertThat(registry.get("sandbox.session.active").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void toolInvokeRecordsCounterAndTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SandboxMetrics metrics = new SandboxMetrics(registry);
        long start = System.nanoTime() - 1_000_000L;
        metrics.recordToolInvoke("exec", false, start);
        assertThat(registry.get("sandbox.tool.invoke")
                .tag("tool", "exec")
                .tag("status", "fail")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("sandbox.tool.invoke.duration")
                .tag("tool", "exec")
                .tag("status", "fail")
                .timer()
                .count()).isEqualTo(1L);
    }
}
