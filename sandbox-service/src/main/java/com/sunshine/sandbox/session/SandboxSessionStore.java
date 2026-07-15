package com.sunshine.sandbox.session;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SandboxSessionStore {

    private final ConcurrentHashMap<String, SandboxSession> sessions = new ConcurrentHashMap<>();

    public void put(SandboxSession session) {
        sessions.put(session.sessionId(), session);
    }

    public Optional<SandboxSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public SandboxSession remove(String sessionId) {
        return sessions.remove(sessionId);
    }
}
