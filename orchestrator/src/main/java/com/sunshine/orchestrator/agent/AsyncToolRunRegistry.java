package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 异步长工具 run 句柄 — await 预算、墙钟、并发槽；不直接 kill（委托 cancellable/spawn registry）。
 */
@Slf4j
@Component
public class AsyncToolRunRegistry {

    private final AgentExecutionProperties executionProperties;
    private final ConcurrentHashMap<String, Handle> byRunId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> slotsByMessageId = new ConcurrentHashMap<>();

    public AsyncToolRunRegistry(AgentExecutionProperties executionProperties) {
        this.executionProperties = executionProperties;
    }

    public enum Kind {
        SANDBOX_EXEC,
        SPAWN_SUBAGENT
    }

    public enum Status {
        RUNNING,
        DONE,
        ERROR,
        CANCELLED,
        BUDGET_EXHAUSTED,
        WALL_TIMEOUT
    }

    public record Snapshot(
            String runId,
            Kind kind,
            Status status,
            int waitCount,
            int waitBudget,
            long elapsedMs,
            String result,
            String partial,
            String error) {
    }

    public String register(Kind kind, String messageId, String conversationId, long wallTimeoutMs) {
        return registerWithId(UUID.randomUUID().toString(), kind, messageId, conversationId, wallTimeoutMs, null);
    }

    public String registerWithId(
            String runId, Kind kind, String messageId, String conversationId, long wallTimeoutMs) {
        return registerWithId(runId, kind, messageId, conversationId, wallTimeoutMs, null);
    }

    /**
     * @param onCancel WALL_TIMEOUT / cancel 时的 kill 回调；须在调度墙钟前写入，避免晚绑定竞态
     */
    public String registerWithId(
            String runId,
            Kind kind,
            String messageId,
            String conversationId,
            long wallTimeoutMs,
            Runnable onCancel) {
        if (!StringUtils.hasText(runId) || kind == null) {
            throw new IllegalArgumentException("runId and kind required");
        }
        String id = runId.strip();
        long now = System.currentTimeMillis();
        long deadline = now + Math.max(0, wallTimeoutMs);
        Handle handle = new Handle(id, kind, messageId, conversationId, now, deadline, onCancel);
        byRunId.put(id, handle);
        scheduleWallTimeout(id, deadline);
        return id;
    }

    public boolean tryAcquireSlot(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return false;
        }
        String mid = messageId.strip();
        int max = maxConcurrentPerMessage();
        AtomicInteger slots = slotsByMessageId.computeIfAbsent(mid, k -> new AtomicInteger(0));
        while (true) {
            int cur = slots.get();
            if (cur >= max) {
                return false;
            }
            if (slots.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    public void releaseSlot(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        AtomicInteger slots = slotsByMessageId.get(messageId.strip());
        if (slots == null) {
            return;
        }
        while (true) {
            int cur = slots.get();
            if (cur <= 0) {
                return;
            }
            if (slots.compareAndSet(cur, cur - 1)) {
                return;
            }
        }
    }

    public void complete(String runId, Status terminal, String result) {
        if (!StringUtils.hasText(runId) || terminal == null || terminal == Status.RUNNING) {
            return;
        }
        Handle handle = byRunId.get(runId.strip());
        if (handle == null) {
            return;
        }
        if (!handle.status.compareAndSet(Status.RUNNING, terminal)) {
            handle.terminalSignal.complete(null);
            return;
        }
        handle.result = result;
        handle.terminalSignal.complete(null);
        releaseSlotOnce(handle);
        if (terminal == Status.CANCELLED || terminal == Status.WALL_TIMEOUT) {
            fireCancelRequest(handle);
        }
    }

    /**
     * 注册 / 补绑 WALL_TIMEOUT / cancel 时的 kill 回调。
     * 若已处于 CANCELLED / WALL_TIMEOUT 且尚未触发，立即执行一次（晚绑定兜底）。
     */
    public void onCancelRequest(String runId, Runnable action) {
        if (!StringUtils.hasText(runId) || action == null) {
            return;
        }
        Handle handle = byRunId.get(runId.strip());
        if (handle == null) {
            return;
        }
        handle.cancelRequest = action;
        Status status = handle.status.get();
        if (status == Status.CANCELLED || status == Status.WALL_TIMEOUT) {
            fireCancelRequest(handle);
        }
    }

    public void updatePartial(String runId, String partial) {
        if (!StringUtils.hasText(runId)) {
            return;
        }
        Handle handle = byRunId.get(runId.strip());
        if (handle != null && partial != null) {
            handle.partial = partial;
        }
    }

    public Snapshot await(String runId, int timeoutSec) {
        if (!StringUtils.hasText(runId)) {
            return null;
        }
        Handle handle = byRunId.get(runId.strip());
        if (handle == null) {
            return null;
        }
        if (isTerminal(handle.status.get())) {
            return toSnapshot(handle, handle.status.get());
        }
        int maxWaits = awaitMaxWaits(handle.kind);
        if (handle.waitCount.get() >= maxWaits) {
            return toSnapshot(handle, Status.BUDGET_EXHAUSTED);
        }
        int clampedSec = clampTimeoutSec(timeoutSec, handle.kind);
        handle.waitCount.incrementAndGet();
        try {
            handle.terminalSignal.get(clampedSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return toSnapshot(handle, Status.RUNNING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            complete(runId, Status.CANCELLED, handle.partial);
            return peek(runId);
        } catch (Exception e) {
            log.warn("[AsyncToolRun] await failed runId={}: {}", runId, e.getMessage());
            return toSnapshot(handle, handle.status.get());
        }
        return toSnapshot(handle, handle.status.get());
    }

    public Snapshot peek(String runId) {
        if (!StringUtils.hasText(runId)) {
            return null;
        }
        Handle handle = byRunId.get(runId.strip());
        if (handle == null) {
            return null;
        }
        return toSnapshot(handle, handle.status.get());
    }

    /** 标 CANCELLED 并唤醒 Future；不直接 kill（由调用方委托 cancellable/spawn registry）。 */
    public boolean cancel(String runId) {
        if (!StringUtils.hasText(runId)) {
            return false;
        }
        Handle handle = byRunId.get(runId.strip());
        if (handle == null) {
            return false;
        }
        complete(runId, Status.CANCELLED, handle.result);
        return true;
    }

    public List<String> listRunningByMessage(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return List.of();
        }
        String mid = messageId.strip();
        List<String> running = new ArrayList<>();
        for (Handle handle : byRunId.values()) {
            if (mid.equals(handle.messageId) && handle.status.get() == Status.RUNNING) {
                running.add(handle.runId);
            }
        }
        return List.copyOf(running);
    }

    /**
     * 主会话 stop：将该 message 下全部 RUNNING 标 CANCELLED 并唤醒 await。
     * kill 经 register 时绑定的 onCancel（EXEC→cancellable / SPAWN→spawn registry）。
     */
    public int cancelByMessage(String messageId) {
        int cancelled = 0;
        for (String runId : listRunningByMessage(messageId)) {
            if (cancel(runId)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    private void scheduleWallTimeout(String runId, long deadlineAtMs) {
        long delayMs = deadlineAtMs - System.currentTimeMillis();
        // 虚拟线程统一延迟入口：内置定时器计时，到期后转投虚拟线程执行
        VirtualThreadExecutors.scheduleDelayed(() -> fireWallTimeout(runId), delayMs);
    }

    private void fireWallTimeout(String runId) {
        Handle handle = byRunId.get(runId);
        if (handle == null || handle.status.get() != Status.RUNNING) {
            return;
        }
        complete(runId, Status.WALL_TIMEOUT, handle.partial);
    }

    private void releaseSlotOnce(Handle handle) {
        if (handle.slotReleased.compareAndSet(false, true) && StringUtils.hasText(handle.messageId)) {
            releaseSlot(handle.messageId);
        }
    }

    private void fireCancelRequest(Handle handle) {
        Runnable action = handle.cancelRequest;
        if (action == null || !handle.cancelRequestFired.compareAndSet(false, true)) {
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[AsyncToolRun] onCancelRequest failed runId={}: {}", handle.runId, e.getMessage());
        }
    }

    private Snapshot toSnapshot(Handle handle, Status responseStatus) {
        return new Snapshot(
                handle.runId,
                handle.kind,
                responseStatus,
                handle.waitCount.get(),
                awaitMaxWaits(handle.kind),
                System.currentTimeMillis() - handle.startedAtMs,
                handle.result,
                handle.partial,
                handle.error);
    }

    /** timeoutSec≤0 → 该 kind 默认；再夹到 kind 上限；结果至少 1s。 */
    private int clampTimeoutSec(int timeoutSec, Kind kind) {
        AgentExecutionProperties.React.AsyncTool cfg = executionProperties.getReact().getAsyncTool();
        int effective = timeoutSec > 0 ? timeoutSec : awaitDefaultSec(kind, cfg);
        int max = awaitMaxSec(kind, cfg);
        if (max > 0 && effective > max) {
            effective = max;
        }
        return Math.max(1, effective);
    }

    private int awaitDefaultSec(Kind kind, AgentExecutionProperties.React.AsyncTool cfg) {
        if (kind == Kind.SPAWN_SUBAGENT) {
            int n = cfg.getSpawnAwaitDefaultSec();
            return n > 0 ? n : 120;
        }
        int n = cfg.getAwaitDefaultSec();
        return n > 0 ? n : 30;
    }

    private int awaitMaxSec(Kind kind, AgentExecutionProperties.React.AsyncTool cfg) {
        if (kind == Kind.SPAWN_SUBAGENT) {
            int n = cfg.getSpawnAwaitMaxSec();
            return n > 0 ? n : 200;
        }
        int n = cfg.getAwaitMaxSec();
        return n > 0 ? n : 120;
    }

    private int awaitMaxWaits(Kind kind) {
        AgentExecutionProperties.React.AsyncTool cfg = executionProperties.getReact().getAsyncTool();
        if (kind == Kind.SPAWN_SUBAGENT) {
            int n = cfg.getSpawnAwaitMaxWaits();
            return n > 0 ? n : 3;
        }
        int n = cfg.getAwaitMaxWaits();
        return n > 0 ? n : 3;
    }

    private int maxConcurrentPerMessage() {
        int n = executionProperties.getReact().getAsyncTool().getMaxConcurrentPerMessage();
        return n > 0 ? n : 3;
    }

    private static boolean isTerminal(Status status) {
        return status != null && status != Status.RUNNING;
    }

    private static final class Handle {
        private final String runId;
        private final Kind kind;
        private final String messageId;
        private final String conversationId;
        private final AtomicReference<Status> status = new AtomicReference<>(Status.RUNNING);
        private final AtomicInteger waitCount = new AtomicInteger(0);
        private final long startedAtMs;
        private final long deadlineAtMs;
        private volatile String result;
        private volatile String partial;
        private volatile String error;
        private volatile Runnable cancelRequest;
        private final AtomicBoolean cancelRequestFired = new AtomicBoolean(false);
        private final CompletableFuture<Void> terminalSignal = new CompletableFuture<>();
        private final AtomicBoolean slotReleased = new AtomicBoolean(false);

        Handle(
                String runId,
                Kind kind,
                String messageId,
                String conversationId,
                long startedAtMs,
                long deadlineAtMs,
                Runnable onCancel) {
            this.runId = runId;
            this.kind = kind;
            this.messageId = StringUtils.hasText(messageId) ? messageId.strip() : null;
            this.conversationId = StringUtils.hasText(conversationId) ? conversationId.strip() : null;
            this.startedAtMs = startedAtMs;
            this.deadlineAtMs = deadlineAtMs;
            this.cancelRequest = onCancel;
        }
    }
}
