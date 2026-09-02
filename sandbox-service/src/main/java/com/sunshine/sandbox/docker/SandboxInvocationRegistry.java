package com.sunshine.sandbox.docker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进行中沙箱调用句柄 — Process（docker exec）或协作取消标志（host 侧 glob/grep）。
 * cancel 须校验 sessionId，防止跨会话误杀。
 */
@Slf4j
@Component
public class SandboxInvocationRegistry {

    private final ConcurrentHashMap<String, Handle> byId = new ConcurrentHashMap<>();

    public void bindProcess(String sessionId, String invocationId, Process process) {
        if (!StringUtils.hasText(invocationId) || process == null) {
            return;
        }
        String id = invocationId.strip();
        Handle handle = byId.computeIfAbsent(id, k -> new Handle());
        if (!bindSession(handle, sessionId, id)) {
            return;
        }
        handle.processRef.set(process);
        if (handle.cancelled.get()) {
            safeDestroy(process);
        }
    }

    public AtomicBoolean bindFlag(String sessionId, String invocationId) {
        if (!StringUtils.hasText(invocationId)) {
            return new AtomicBoolean(false);
        }
        String id = invocationId.strip();
        Handle handle = byId.computeIfAbsent(id, k -> new Handle());
        if (!bindSession(handle, sessionId, id)) {
            return new AtomicBoolean(false);
        }
        return handle.cancelled;
    }

    /**
     * @return false 若 invocation 已绑定其它 session
     */
    public boolean cancel(String sessionId, String invocationId) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(invocationId)) {
            return false;
        }
        String id = invocationId.strip();
        String sid = sessionId.strip();
        Handle handle = byId.computeIfAbsent(id, k -> new Handle());
        if (StringUtils.hasText(handle.sessionId) && !handle.sessionId.equals(sid)) {
            log.warn("[SandboxInvocation] cancel session mismatch invocationId={} bound={} req={}",
                    id, handle.sessionId, sid);
            return false;
        }
        handle.sessionId = sid;
        handle.cancelled.set(true);
        Process p = handle.processRef.get();
        if (p != null) {
            safeDestroy(p);
            log.info("[SandboxInvocation] destroy process invocationId={} sessionId={}", id, sid);
            return true;
        }
        log.info("[SandboxInvocation] flag-cancel invocationId={} sessionId={}", id, sid);
        return true;
    }

    public boolean isCancelled(String invocationId) {
        if (!StringUtils.hasText(invocationId)) {
            return false;
        }
        Handle handle = byId.get(invocationId.strip());
        return handle != null && handle.cancelled.get();
    }

    public void unbind(String invocationId) {
        if (!StringUtils.hasText(invocationId)) {
            return;
        }
        byId.remove(invocationId.strip());
    }

    private static boolean bindSession(Handle handle, String sessionId, String invocationId) {
        if (!StringUtils.hasText(sessionId)) {
            return true;
        }
        String sid = sessionId.strip();
        if (!StringUtils.hasText(handle.sessionId)) {
            handle.sessionId = sid;
            return true;
        }
        if (handle.sessionId.equals(sid)) {
            return true;
        }
        log.warn("[SandboxInvocation] bind session mismatch invocationId={} bound={} req={}",
                invocationId, handle.sessionId, sid);
        return false;
    }

    private static void safeDestroy(Process p) {
        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static final class Handle {
        private volatile String sessionId;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Process> processRef = new AtomicReference<>();
    }
}
