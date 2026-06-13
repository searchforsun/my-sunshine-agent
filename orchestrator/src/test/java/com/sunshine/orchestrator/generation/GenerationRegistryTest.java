package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GenerationRegistryTest {

    @Mock
    private GenerationStreamService streamService;

    @Mock
    private GenerationProperties properties;

    @Mock
    private GenerationFlushScheduler flushScheduler;

    private GenerationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new GenerationRegistry();
    }

    private GenerationJob newJob(String generationId, String messageId) {
        return new GenerationJob(
                generationId, messageId, "conv-1", "alice", "default", "chat",
                streamService, properties, flushScheduler);
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

        verify(flushScheduler).commitFinal("msg-2", "", "", MessageStatus.INTERRUPTED, null);
        assertThat(registry.get("gen-2")).isEmpty();
        assertThat(registry.tryLockMessage("msg-2", "gen-3")).isTrue();
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
}
