package com.sunshine.orchestrator.hitl;

import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 同一会话写操作 HITL 串行 — 避免模型并行 tool call 时多条确认框同时弹出 */
@Component
public class HitlWriteToolSerialGate {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void runExclusive(String scopeKey, Runnable action) {
        callExclusive(scopeKey, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callExclusive(String scopeKey, Callable<T> action) {
        if (scopeKey == null || scopeKey.isBlank()) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        String key = scopeKey.strip();
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(key, lock);
            }
        }
    }
}
