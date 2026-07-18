package com.sunshine.sandbox.docker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进行中沙箱调用句柄 — Process（docker exec）或协作取消标志（host 侧 glob/grep）。
 */
@Slf4j
@Component
public class SandboxInvocationRegistry {

    private final ConcurrentHashMap<String, Handle> byId = new ConcurrentHashMap<>();

    public void bindProcess(String invocationId, Process process) {
        if (!StringUtils.hasText(invocationId) || process == null) {
            return;
        }
        String id = invocationId.strip();
        Handle handle = byId.computeIfAbsent(id, k -> new Handle());
        handle.processRef.set(process);
        if (handle.cancelled.get()) {
            safeDestroy(process);
        }
    }

    public AtomicBoolean bindFlag(String invocationId) {
        if (!StringUtils.hasText(invocationId)) {
            return new AtomicBoolean(false);
        }
        String id = invocationId.strip();
        Handle handle = byId.computeIfAbsent(id, k -> new Handle());
        return handle.cancelled;
    }

    public boolean cancel(String invocationId) {
        if (!StringUtils.hasText(invocationId)) {
            return false;
        }
        String id = invocationId.strip();
        Handle handle = byId.computeIfAbsent(id, k -> new Handle());
        handle.cancelled.set(true);
        Process p = handle.processRef.get();
        if (p != null) {
            safeDestroy(p);
            log.info("[SandboxInvocation] destroy process invocationId={}", id);
            return true;
        }
        log.info("[SandboxInvocation] flag-cancel invocationId={}", id);
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

    private static void safeDestroy(Process p) {
        try {
            p.destroyForcibly();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Process> processRef = new AtomicReference<>();
    }
}
