package com.sunshine.llm.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轻量熔断器 — 连续失败达阈值后短暂 OPEN，避免反复打挂掉的厂商。
 * 生产可对接 Sentinel Dashboard 规则；此处保证单测与本地降级可用。
 */
@Slf4j
@Component
public class AdapterCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public boolean allowRequest(String model) {
        State state = states.computeIfAbsent(model, k -> new State());
        synchronized (state) {
            if (state.openUntil != null && Instant.now().isBefore(state.openUntil)) {
                return false;
            }
            if (state.openUntil != null && Instant.now().isAfter(state.openUntil)) {
                state.openUntil = null;
                state.failures.set(0);
            }
            return true;
        }
    }

    public void recordSuccess(String model) {
        State state = states.computeIfAbsent(model, k -> new State());
        synchronized (state) {
            state.failures.set(0);
            state.openUntil = null;
        }
    }

    public void recordFailure(String model) {
        State state = states.computeIfAbsent(model, k -> new State());
        synchronized (state) {
            int count = state.failures.incrementAndGet();
            if (count >= FAILURE_THRESHOLD) {
                state.openUntil = Instant.now().plus(OPEN_DURATION);
                log.warn("[LLM-GW] 熔断 OPEN model={} {}s", model, OPEN_DURATION.toSeconds());
            }
        }
    }

    private static final class State {
        private final AtomicInteger failures = new AtomicInteger();
        private Instant openUntil;
    }
}
