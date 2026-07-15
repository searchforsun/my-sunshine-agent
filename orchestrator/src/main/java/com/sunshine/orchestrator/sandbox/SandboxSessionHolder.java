package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.catalog.SandboxPolicy;

/**
 * 当前 Agent run 的沙箱会话绑定（ThreadLocal）。
 * T13 在 createSession / closeSession 时 bind / unbind。
 */
public final class SandboxSessionHolder {

    public record Binding(String sessionId, SandboxPolicy policy) {}

    private static final ThreadLocal<Binding> HOLDER = new ThreadLocal<>();

    public static void bind(String sessionId, SandboxPolicy policy) {
        HOLDER.set(new Binding(sessionId, policy));
    }

    public static Binding current() {
        return HOLDER.get();
    }

    public static String requireSessionId() {
        Binding binding = HOLDER.get();
        if (binding == null || binding.sessionId() == null || binding.sessionId().isBlank()) {
            throw new IllegalStateException("sandbox session not bound on current thread");
        }
        return binding.sessionId();
    }

    /** @return 解绑前的 sessionId，未绑定则 null */
    public static String unbind() {
        Binding binding = HOLDER.get();
        HOLDER.remove();
        return binding != null ? binding.sessionId() : null;
    }

    private SandboxSessionHolder() {}
}
