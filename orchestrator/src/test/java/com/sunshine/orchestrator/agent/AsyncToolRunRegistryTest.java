package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncToolRunRegistryTest {
    private AsyncToolRunRegistry registry;

    @BeforeEach
    void setUp() {
        AgentExecutionProperties props = new AgentExecutionProperties();
        // 槽位测试固定 3（生产默认已调至 10）
        props.getReact().getAsyncTool().setMaxConcurrentPerMessage(3);
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
    void onCancelRequest_boundAtRegister_runsOnWallTimeoutAndCancel() throws Exception {
        java.util.concurrent.atomic.AtomicInteger kills = new java.util.concurrent.atomic.AtomicInteger();
        String wallRun = registry.registerWithId(
                "wall-run",
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC,
                "msg-wall",
                "c1",
                50L,
                kills::incrementAndGet);
        assertThat(awaitStatus(wallRun, AsyncToolRunRegistry.Status.WALL_TIMEOUT, 3_000)).isTrue();
        assertThat(kills.get()).isEqualTo(1);

        String cancelRun = registry.registerWithId(
                "cancel-run",
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC,
                "msg-cancel",
                "c1",
                600_000L,
                kills::incrementAndGet);
        assertThat(registry.cancel(cancelRun)).isTrue();
        assertThat(registry.peek(cancelRun).status()).isEqualTo(AsyncToolRunRegistry.Status.CANCELLED);
        assertThat(kills.get()).isEqualTo(2);
    }

    @Test
    void onCancelRequest_lateBind_firesOnceIfAlreadyTerminal() {
        java.util.concurrent.atomic.AtomicInteger kills = new java.util.concurrent.atomic.AtomicInteger();
        String runId = registry.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-late", "c1", 600_000L);
        assertThat(registry.cancel(runId)).isTrue();
        registry.onCancelRequest(runId, kills::incrementAndGet);
        registry.onCancelRequest(runId, kills::incrementAndGet);
        assertThat(kills.get()).isEqualTo(1);
    }

    @Test
    void cancelByMessage_marksAllRunningCancelled() {
        java.util.concurrent.atomic.AtomicInteger kills = new java.util.concurrent.atomic.AtomicInteger();
        String a = registry.registerWithId(
                "run-a",
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC,
                "m",
                "c1",
                600_000L,
                kills::incrementAndGet);
        String b = registry.registerWithId(
                "run-b",
                AsyncToolRunRegistry.Kind.SPAWN_SUBAGENT,
                "m",
                "c1",
                600_000L,
                kills::incrementAndGet);
        java.util.concurrent.atomic.AtomicBoolean otherKill = new java.util.concurrent.atomic.AtomicBoolean();
        String other = registry.registerWithId(
                "run-other",
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC,
                "other",
                "c1",
                600_000L,
                () -> otherKill.set(true));
        assertThat(registry.cancelByMessage("m")).isEqualTo(2);
        assertThat(registry.peek(a).status()).isEqualTo(AsyncToolRunRegistry.Status.CANCELLED);
        assertThat(registry.peek(b).status()).isEqualTo(AsyncToolRunRegistry.Status.CANCELLED);
        assertThat(registry.peek(other).status()).isEqualTo(AsyncToolRunRegistry.Status.RUNNING);
        assertThat(kills.get()).isEqualTo(2);
        assertThat(otherKill.get()).isFalse();
    }

    @Test
    void await_spawn_usesSpawnMaxAndDefault() throws Exception {
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.getReact().getAsyncTool().setSpawnAwaitDefaultSec(2);
        props.getReact().getAsyncTool().setSpawnAwaitMaxSec(2);
        props.getReact().getAsyncTool().setAwaitMaxSec(120);
        AsyncToolRunRegistry kindAware = new AsyncToolRunRegistry(props);

        String spawnId = kindAware.register(
                AsyncToolRunRegistry.Kind.SPAWN_SUBAGENT, "msg-s", "c1", 600_000L);
        long t0 = System.currentTimeMillis();
        assertThat(kindAware.await(spawnId, 999).status()).isEqualTo(AsyncToolRunRegistry.Status.RUNNING);
        assertThat(System.currentTimeMillis() - t0).isLessThan(5_000L);

        String spawnDefault = kindAware.register(
                AsyncToolRunRegistry.Kind.SPAWN_SUBAGENT, "msg-s2", "c1", 600_000L);
        long t1 = System.currentTimeMillis();
        assertThat(kindAware.await(spawnDefault, 0).status()).isEqualTo(AsyncToolRunRegistry.Status.RUNNING);
        assertThat(System.currentTimeMillis() - t1).isGreaterThanOrEqualTo(1_500L);
        assertThat(System.currentTimeMillis() - t1).isLessThan(5_000L);

        String execId = kindAware.register(
                AsyncToolRunRegistry.Kind.SANDBOX_EXEC, "msg-e", "c1", 600_000L);
        props.getReact().getAsyncTool().setAwaitDefaultSec(1);
        props.getReact().getAsyncTool().setAwaitMaxSec(1);
        long t2 = System.currentTimeMillis();
        assertThat(kindAware.await(execId, 999).status()).isEqualTo(AsyncToolRunRegistry.Status.RUNNING);
        assertThat(System.currentTimeMillis() - t2).isLessThan(3_000L);
    }

    @Test
    void await_spawn_waitBudgetUsesSpawnMaxWaits() {
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.getReact().getAsyncTool().setSpawnAwaitMaxWaits(2);
        props.getReact().getAsyncTool().setAwaitMaxWaits(3);
        AsyncToolRunRegistry kindAware = new AsyncToolRunRegistry(props);
        String runId = kindAware.register(
                AsyncToolRunRegistry.Kind.SPAWN_SUBAGENT, "msg-b", "c1", 600_000L);
        assertThat(kindAware.await(runId, 1).waitBudget()).isEqualTo(2);
        assertThat(kindAware.await(runId, 1).waitCount()).isEqualTo(2);
        assertThat(kindAware.await(runId, 1).status()).isEqualTo(AsyncToolRunRegistry.Status.BUDGET_EXHAUSTED);
    }

    @Test
    void await_worker_usesWorkerMaxDefaultAndWaitBudget() throws Exception {
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.getReact().getAsyncTool().setWorkerAwaitDefaultSec(2);
        props.getReact().getAsyncTool().setWorkerAwaitMaxSec(3);
        props.getReact().getAsyncTool().setWorkerAwaitMaxWaits(2);
        AsyncToolRunRegistry kindAware = new AsyncToolRunRegistry(props);

        // worker 默认观察窗口按 worker-await-default-sec（2s）
        String workerDefault = kindAware.register(
                AsyncToolRunRegistry.Kind.WORKER_DISPATCH, "msg-w", "c1", 600_000L);
        long t0 = System.currentTimeMillis();
        assertThat(kindAware.await(workerDefault, 0).status()).isEqualTo(AsyncToolRunRegistry.Status.RUNNING);
        assertThat(System.currentTimeMillis() - t0).isGreaterThanOrEqualTo(1_500L);
        assertThat(System.currentTimeMillis() - t0).isLessThan(5_000L);

        // worker 预算按 worker-await-max-waits（2 次），超限 BUDGET_EXHAUSTED
        String runId = kindAware.register(
                AsyncToolRunRegistry.Kind.WORKER_DISPATCH, "msg-w2", "c1", 600_000L);
        assertThat(kindAware.await(runId, 1).waitBudget()).isEqualTo(2);
        assertThat(kindAware.await(runId, 1).waitCount()).isEqualTo(2);
        assertThat(kindAware.await(runId, 1).status()).isEqualTo(AsyncToolRunRegistry.Status.BUDGET_EXHAUSTED);
    }

    @Test
    void awaitMany_allTerminal_returnsAllSnapshotsInOrder() {
        String a = registry.registerWithId(
                "m-a", AsyncToolRunRegistry.Kind.WORKER_DISPATCH, "msg-m", "c1", 600_000L);
        String b = registry.registerWithId(
                "m-b", AsyncToolRunRegistry.Kind.WORKER_DISPATCH, "msg-m", "c1", 600_000L);
        registry.complete(a, AsyncToolRunRegistry.Status.DONE, "handoff-a");
        registry.complete(b, AsyncToolRunRegistry.Status.ERROR, "boom");

        List<AsyncToolRunRegistry.Snapshot> snaps =
                registry.awaitMany(List.of(b, a, "nope", " "), 1);

        assertThat(snaps).hasSize(2);
        assertThat(snaps.get(0).runId()).isEqualTo("m-b");
        assertThat(snaps.get(0).status()).isEqualTo(AsyncToolRunRegistry.Status.ERROR);
        assertThat(snaps.get(0).result()).isEqualTo("boom");
        assertThat(snaps.get(1).runId()).isEqualTo("m-a");
        assertThat(snaps.get(1).status()).isEqualTo(AsyncToolRunRegistry.Status.DONE);
        assertThat(snaps.get(1).result()).isEqualTo("handoff-a");
        // 已终态不消耗 waitCount（语义同单 run await）
        assertThat(snaps.get(0).waitCount()).isZero();
        assertThat(snaps.get(1).waitCount()).isZero();
    }

    @Test
    void awaitMany_mixedDoneAndRunning_wakesOnCompleteSharedWindow() throws Exception {
        String a = registry.registerWithId(
                "m-a", AsyncToolRunRegistry.Kind.WORKER_DISPATCH, "msg-m", "c1", 600_000L);
        String b = registry.registerWithId(
                "m-b", AsyncToolRunRegistry.Kind.WORKER_DISPATCH, "msg-m", "c1", 600_000L);
        registry.complete(a, AsyncToolRunRegistry.Status.DONE, "done-a");
        Executors.newSingleThreadScheduledExecutor().schedule(
                () -> registry.complete(b, AsyncToolRunRegistry.Status.DONE, "done-b"),
                200, TimeUnit.MILLISECONDS);

        List<AsyncToolRunRegistry.Snapshot> snaps = registry.awaitMany(List.of(a, b), 5);

        assertThat(snaps).hasSize(2);
        assertThat(snaps.get(0).status()).isEqualTo(AsyncToolRunRegistry.Status.DONE);
        assertThat(snaps.get(1).status()).isEqualTo(AsyncToolRunRegistry.Status.DONE);
        assertThat(snaps.get(1).result()).isEqualTo("done-b");
        assertThat(snaps.get(1).waitCount()).isEqualTo(1);
    }

    @Test
    void awaitMany_nullOrEmptyOrBlank_returnsEmpty() {
        assertThat(registry.awaitMany(null, 1)).isEmpty();
        assertThat(registry.awaitMany(List.of(), 1)).isEmpty();
        assertThat(registry.awaitMany(List.of("  ", ""), 1)).isEmpty();
        assertThat(registry.awaitMany(List.of("unknown-1", "unknown-2"), 1)).isEmpty();
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
