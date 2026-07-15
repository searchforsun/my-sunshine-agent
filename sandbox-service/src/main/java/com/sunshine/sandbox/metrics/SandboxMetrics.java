package com.sunshine.sandbox.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 沙箱 Micrometer 指标。
 * <ul>
 *   <li>{@code sandbox.session.active} — Gauge（SessionStore）</li>
 *   <li>{@code sandbox.tool.invoke} — Counter（tag: tool / status=ok|fail）</li>
 *   <li>{@code sandbox.tool.invoke.duration} — Timer（同上 tag；与 Counter 分名避免冲突）</li>
 * </ul>
 */
@Component
public class SandboxMetrics {

    private final MeterRegistry registry;

    public SandboxMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordToolInvoke(String tool, boolean ok, long startNanos) {
        String toolTag = tool != null && !tool.isBlank() ? tool : "unknown";
        String status = ok ? "ok" : "fail";
        Counter.builder("sandbox.tool.invoke")
                .description("Sandbox tool invoke count")
                .tag("tool", toolTag)
                .tag("status", status)
                .register(registry)
                .increment();
        Timer.builder("sandbox.tool.invoke.duration")
                .description("Sandbox tool invoke latency")
                .tag("tool", toolTag)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
