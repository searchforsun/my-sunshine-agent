package com.sunshine.sandbox.session;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SandboxSessionStore {

    private final ConcurrentHashMap<String, SandboxSession> sessions = new ConcurrentHashMap<>();

    public SandboxSessionStore(MeterRegistry meterRegistry) {
        Gauge.builder("sandbox.session.active", this, SandboxSessionStore::size)
                .description("Active sandbox Docker sessions")
                .register(meterRegistry);
    }

    /** 单测无指标 */
    public SandboxSessionStore() {
    }

    public void put(SandboxSession session) {
        sessions.put(session.sessionId(), session);
    }

    public Optional<SandboxSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public SandboxSession remove(String sessionId) {
        return sessions.remove(sessionId);
    }

    public int size() {
        return sessions.size();
    }
}
