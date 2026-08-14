package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.common.sandbox.SandboxPolicy;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前 Agent run 的沙箱会话绑定 — 按 bridgeId 索引（跨线程）。
 * <p>禁止 ThreadLocal：工具 {@code callAsync} 经虚拟线程调度，
 * 与 {@link SandboxSessionLifecycle#prepareRun} / {@code ensureBound} 不在同一线程。
 */
public final class SandboxSessionHolder {

    public record Binding(String sessionId, SandboxPolicy policy) {}

    private static final ConcurrentHashMap<String, Binding> BY_BRIDGE = new ConcurrentHashMap<>();

    public static void bind(String bridgeId, String sessionId, SandboxPolicy policy) {
        if (bridgeId == null || bridgeId.isBlank()) {
            throw new IllegalArgumentException("sandbox bind requires bridgeId");
        }
        BY_BRIDGE.put(bridgeId.strip(), new Binding(sessionId, policy));
    }

    public static Binding get(String bridgeId) {
        if (bridgeId == null || bridgeId.isBlank()) {
            return null;
        }
        return BY_BRIDGE.get(bridgeId.strip());
    }

    public static Binding current() {
        String bridgeId = resolveBridgeId();
        return bridgeId != null ? BY_BRIDGE.get(bridgeId) : null;
    }

    public static String requireSessionId() {
        return requireSessionId(resolveBridgeId());
    }

    public static String requireSessionId(String bridgeId) {
        Binding binding = get(bridgeId);
        if (binding == null) {
            binding = current();
        }
        if (binding == null || binding.sessionId() == null || binding.sessionId().isBlank()) {
            throw new IllegalStateException("sandbox session not bound for current agent run");
        }
        return binding.sessionId();
    }

    /** @return 解绑前的 sessionId，未绑定则 null */
    public static String unbind(String bridgeId) {
        if (bridgeId == null || bridgeId.isBlank()) {
            return null;
        }
        Binding binding = BY_BRIDGE.remove(bridgeId.strip());
        return binding != null ? binding.sessionId() : null;
    }

    /** 单测清理；生产路径请用 {@link #unbind(String)} */
    public static void clearAll() {
        BY_BRIDGE.clear();
    }

    static String resolveBridgeId() {
        String fromHitl = StepEventBridge.resolveHitlBridgeId();
        if (fromHitl != null && BY_BRIDGE.containsKey(fromHitl)) {
            return fromHitl;
        }
        String active = StepEventBridge.activeBridgeId();
        if (active != null && BY_BRIDGE.containsKey(active)) {
            return active;
        }
        if (BY_BRIDGE.size() == 1) {
            return BY_BRIDGE.keySet().iterator().next();
        }
        return fromHitl != null ? fromHitl : active;
    }

    private SandboxSessionHolder() {}
}
