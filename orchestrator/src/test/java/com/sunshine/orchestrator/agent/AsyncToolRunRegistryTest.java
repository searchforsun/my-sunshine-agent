package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncToolRunRegistryTest {
    private AsyncToolRunRegistry registry;

    @BeforeEach
    void setUp() {
        AgentExecutionProperties props = new AgentExecutionProperties();
        registry = new AsyncToolRunRegistry(props);
    }

    @Test
    void await_terminalPeek_doesNotBurnBudget() {
        String runId = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-1", "c1", 60_000L);
        registry.complete(runId, AsyncToolRunRegistry.Status.DONE, "hello");
        var s1 = registry.await(runId, 1);
        var s2 = registry.await(runId, 1);
        assertThat(s1.status()).isEqualTo(AsyncToolRunRegistry.Status.DONE);
        assertThat(s1.result()).isEqualTo("hello");
        assertThat(s1.waitCount()).isZero();
        assertThat(s2.waitCount()).isZero();
    }

    @Test
    void await_threeWaitsThenBudgetExhausted() throws Exception {
        String runId = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-1", "c1", 600_000L);
        assertThat(registry.await(runId, 1).status()).isEqualTo(AsyncToolRunRegistry.Status.RUNNING);
        assertThat(registry.await(runId, 1).waitCount()).isEqualTo(2);
        assertThat(registry.await(runId, 1).waitCount()).isEqualTo(3);
        var exhausted = registry.await(runId, 30);
        assertThat(exhausted.status()).isEqualTo(AsyncToolRunRegistry.Status.BUDGET_EXHAUSTED);
        assertThat(exhausted.waitCount()).isEqualTo(3);
    }

    @Test
    void await_wakesOnCompleteBeforeTimeout() throws Exception {
        String runId = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-1", "c1", 600_000L);
        Executors.newSingleThreadScheduledExecutor().schedule(
                () -> registry.complete(runId, AsyncToolRunRegistry.Status.DONE, "ok"),
                200, TimeUnit.MILLISECONDS);
        var s = registry.await(runId, 5);
        assertThat(s.status()).isEqualTo(AsyncToolRunRegistry.Status.DONE);
        assertThat(s.result()).isEqualTo("ok");
        assertThat(s.waitCount()).isEqualTo(1);
    }

    @Test
    void tryAcquireSlot_respectsMaxConcurrent() {
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isFalse();
        registry.releaseSlot("msg-1");
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
    }

    @Test
    void unknownRunId_peekReturnsNull() {
        assertThat(registry.peek("nope")).isNull();
    }

    @Test
    void await_interrupted_releasesSlotForMessage() throws Exception {
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
        assertThat(registry.tryAcquireSlot("msg-1")).isFalse();

        String runId = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-1", "c1", 600_000L);

        Thread waiter = new Thread(() -> registry.await(runId, 30));
        waiter.start();
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(5000);

        assertThat(waiter.isAlive()).isFalse();
        assertThat(registry.peek(runId).status()).isEqualTo(AsyncToolRunRegistry.Status.CANCELLED);
        assertThat(registry.tryAcquireSlot("msg-1")).isTrue();
    }

    @Test
    void onCancelRequest_runsOnWallTimeoutAndCancel() throws Exception {
        java.util.concurrent.atomic.AtomicInteger kills = new java.util.concurrent.atomic.AtomicInteger();
        String wallRun = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-wall", "c1", 50L);
        registry.onCancelRequest(wallRun, kills::incrementAndGet);
        assertThat(awaitStatus(wallRun, AsyncToolRunRegistry.Status.WALL_TIMEOUT, 3_000)).isTrue();
        assertThat(kills.get()).isEqualTo(1);

        String cancelRun = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-cancel", "c1", 600_000L);
        registry.onCancelRequest(cancelRun, kills::incrementAndGet);
        assertThat(registry.cancel(cancelRun)).isTrue();
        assertThat(registry.peek(cancelRun).status()).isEqualTo(AsyncToolRunRegistry.Status.CANCELLED);
        assertThat(kills.get()).isEqualTo(2);
    }

    private boolean awaitStatus(String runId, AsyncToolRunRegistry.Status expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var snap = registry.peek(runId);
            if (snap != null && snap.status() == expected) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        var snap = registry.peek(runId);
        return snap != null && snap.status() == expected;
    }
}
