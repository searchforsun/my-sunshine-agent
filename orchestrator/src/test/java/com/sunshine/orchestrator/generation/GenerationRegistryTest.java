package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.agent.AsyncToolRunRegistry;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, TimelineLabelJUnitExtension.class})
class GenerationRegistryTest {

    @Mock
    private GenerationStreamService streamService;

    @Mock
    private GenerationProperties properties;

    @Mock
    private GenerationFlushScheduler flushScheduler;

    @Mock
    private WorkflowPauseService workflowPauseService;

    @Mock
    private AsyncToolRunRegistry asyncToolRunRegistry;

    private GenerationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new GenerationRegistry(workflowPauseService);
        ReflectionTestUtils.setField(registry, "asyncToolRunRegistry", asyncToolRunRegistry);
    }

    @AfterEach
    void tearDown() {
        StepEventBridge.clear("msg-1");
        StepEventBridge.clear("msg-2");
        StepEventBridge.clear("msg-3");
        StepEventBridge.clear("msg-a");
        StepEventBridge.clear("msg-b");
        StepEventBridge.clear("msg-stale");
        StepEventBridge.clear("msg-async");
    }

    private GenerationJob newJob(String generationId, String messageId) {
        GenerationJob job = new GenerationJob(
                generationId, messageId, "conv-1", "alice", "default", "chat", "hello",
                streamService, properties, flushScheduler, null,
                workflowPauseService, mock(com.sunshine.orchestrator.plan.ExecutionPlanStore.class),
                new AgentPauseProperties(), null);
        job.bindStreamEpoch(StepEventBridge.bumpStreamEpoch(messageId));
        return job;
    }

    @Test
    @DisplayName("register / get / remove")
    void registerGetRemove() {
        GenerationJob job = newJob("gen-1", "msg-1");
        registry.register(job);

        assertThat(registry.get("gen-1")).containsSame(job);

        registry.remove("gen-1");
        assertThat(registry.get("gen-1")).isEmpty();
    }

    @Test
    @DisplayName("cancel 调用 job.cancel 并从 running 移除")
    void cancel_disposesJobAndRemoves() {
        GenerationJob job = newJob("gen-2", "msg-2");
        registry.register(job);
        registry.tryLockMessage("msg-2", "gen-2");

        registry.cancel("gen-2");

        assertThat(registry.get("gen-2")).isEmpty();
        assertThat(registry.tryLockMessage("msg-2", "gen-3")).isTrue();
        verify(asyncToolRunRegistry).cancelByMessage("msg-2");
        // persistFinal 在 boundedElastic 异步落库，须等待 commitFinal
        verify(flushScheduler, timeout(5000))
                .commitFinal("msg-2", "", "", MessageStatus.INTERRUPTED, null, null, null);
    }

    @Test
    @DisplayName("cancel 调用 asyncToolRunRegistry.cancelByMessage")
    void cancel_invokesAsyncCancelByMessage() {
        GenerationJob job = newJob("gen-async", "msg-async");
        registry.register(job);

        registry.cancel("gen-async");

        verify(asyncToolRunRegistry).cancelByMessage("msg-async");
        assertThat(registry.get("gen-async")).isEmpty();
    }

    @Test
    @DisplayName("releaseBlockingWaitsForMessage 调用 cancelByMessage（orphan stop）")
    void releaseBlockingWaitsForMessage_cancelsAsyncRuns() {
        registry.releaseBlockingWaitsForMessage("msg-orphan");
        verify(asyncToolRunRegistry).cancelByMessage("msg-orphan");
    }

    @Test
    @DisplayName("cancel 未知 id 不调用 cancelByMessage")
    void cancel_unknownId_doesNotCancelAsync() {
        registry.cancel("missing");
        verify(asyncToolRunRegistry, never()).cancelByMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("tryLockMessage 冲突时返回 false")
    void tryLockMessage_conflictReturnsFalse() {
        assertThat(registry.tryLockMessage("msg-3", "gen-a")).isTrue();
        assertThat(registry.tryLockMessage("msg-3", "gen-b")).isFalse();
        assertThat(registry.tryLockMessage("msg-4", "gen-c")).isTrue();
    }

    @Test
    @DisplayName("cancel 不存在的 generationId 无操作")
    void cancel_unknownIdIsNoOp() {
        registry.cancel("missing");
        assertThat(registry.get("missing")).isEmpty();
    }

    @Test
    @DisplayName("cancelAll 停止全部 running job")
    void cancelAll_stopsEveryRunningJob() {
        GenerationJob job1 = newJob("gen-a", "msg-a");
        GenerationJob job2 = newJob("gen-b", "msg-b");
        registry.register(job1);
        registry.register(job2);

        registry.cancelAll();

        assertThat(registry.get("gen-a")).isEmpty();
        assertThat(registry.get("gen-b")).isEmpty();
        verify(flushScheduler, timeout(5000))
                .commitFinal(eq("msg-a"), eq(""), eq(""), eq(MessageStatus.INTERRUPTED), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
        verify(flushScheduler, timeout(5000))
                .commitFinal(eq("msg-b"), eq(""), eq(""), eq(MessageStatus.INTERRUPTED), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("clearStaleLockIfNoActiveJob 无 job 时清除遗留锁")
    void clearStaleLockIfNoActiveJob_removesOrphanLock() {
        assertThat(registry.tryLockMessage("msg-stale", "gen-old")).isTrue();
        assertThat(registry.tryLockMessage("msg-stale", "gen-new")).isFalse();
        registry.clearStaleLockIfNoActiveJob("msg-stale");
        assertThat(registry.tryLockMessage("msg-stale", "gen-new")).isTrue();
    }
}
