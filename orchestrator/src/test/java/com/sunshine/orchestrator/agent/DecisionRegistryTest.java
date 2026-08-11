package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionRegistryTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private AgentExecutionProperties executionProperties;
    private DecisionRegistry registry;

    @BeforeEach
    void setUp() {
        executionProperties = new AgentExecutionProperties();
        executionProperties.getReact().getDecision().setTimeoutSec(300);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().doNothing().when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
        lenient().when(redis.delete(anyString())).thenReturn(Boolean.TRUE);
        registry = new DecisionRegistry(executionProperties, redis, new ObjectMapper());
    }

    @Test
    void register_secondAwaitingOnSameMessage_rejected() {
        List<DecisionOption> options = sampleOptions(false);
        registry.register("msg-1", "user-1", "选哪个？", options, false);
        assertThat(registry.hasAwaiting("msg-1")).isTrue();

        assertThatThrownBy(() ->
                registry.register("msg-1", "user-1", "再问一次？", options, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting");
    }

    @Test
    void register_concurrentSameMessage_onlyOneSucceeds() throws Exception {
        List<DecisionOption> options = sampleOptions(false);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    registry.register("msg-race", "user-1", "选哪个？", options, false);
                    success.incrementAndGet();
                } catch (IllegalStateException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(registry.hasAwaiting("msg-race")).isTrue();
    }

    @Test
    void resolve_requireInputBlank_returnsInputRequired_andDoesNotComplete() {
        List<DecisionOption> options = List.of(
                new DecisionOption("plan_a", "方案A", "快", false),
                new DecisionOption("plan_b", "方案B", "全", true));
        DecisionRegistry.Registration reg =
                registry.register("msg-2", "user-1", "选哪个？", options, false);

        DecisionRegistry.ResolveOutcome outcome =
                registry.resolve(reg.token(), "plan_b", "  ", "user-1");

        assertThat(outcome).isEqualTo(DecisionRegistry.ResolveOutcome.INPUT_REQUIRED);
        assertThat(reg.future().isDone()).isFalse();
        assertThat(registry.hasAwaiting("msg-2")).isTrue();
    }

    @Test
    void resolve_validChoice_completesFuture() throws Exception {
        List<DecisionOption> options = sampleOptions(false);
        DecisionRegistry.Registration reg =
                registry.register("msg-3", "user-1", "选哪个？", options, false);

        DecisionRegistry.ResolveOutcome outcome =
                registry.resolve(reg.token(), "plan_a", null, "user-1");

        assertThat(outcome).isEqualTo(DecisionRegistry.ResolveOutcome.ACCEPTED);
        DecisionResult result = reg.future().get(1, TimeUnit.SECONDS);
        assertThat(result.choice()).isEqualTo("plan_a");
        assertThat(result.customInput()).isNull();
        assertThat(result.decidedAt()).isPositive();
        assertThat(registry.hasAwaiting("msg-3")).isFalse();
    }

    @Test
    void cancelWaitersForMessage_cancelsFuture() {
        List<DecisionOption> options = sampleOptions(false);
        DecisionRegistry.Registration reg =
                registry.register("msg-4", "user-1", "选哪个？", options, false);

        registry.cancelWaitersForMessage("msg-4");

        assertThat(reg.future().isCancelled()).isTrue();
        assertThat(registry.hasAwaiting("msg-4")).isFalse();
    }

    @Test
    void register_stripsMessageId_soWhitespaceCannotBypassD15() {
        List<DecisionOption> options = sampleOptions(false);
        registry.register("  msg-d15  ", "user-1", "选哪个？", options, false);

        assertThat(registry.hasAwaiting("msg-d15")).isTrue();
        assertThatThrownBy(() ->
                registry.register("msg-d15", "user-1", "再问？", options, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting");

        registry.cancelWaitersForMessage("  msg-d15  ");
        assertThat(registry.hasAwaiting("msg-d15")).isFalse();
    }

    @Test
    void resolve_invalidChoice_doesNotCompleteFuture() {
        List<DecisionOption> options = sampleOptions(false);
        DecisionRegistry.Registration reg =
                registry.register("msg-5", "user-1", "选哪个？", options, false);

        DecisionRegistry.ResolveOutcome outcome =
                registry.resolve(reg.token(), "not_an_option", null, "user-1");

        assertThat(outcome).isEqualTo(DecisionRegistry.ResolveOutcome.INVALID_CHOICE);
        assertThat(reg.future().isDone()).isFalse();
        assertThat(registry.hasAwaiting("msg-5")).isTrue();
    }

    @Test
    void resolve_messageIdMismatch_returnsNotFound_andDoesNotComplete() {
        List<DecisionOption> options = sampleOptions(false);
        DecisionRegistry.Registration reg =
                registry.register("msg-owner", "user-1", "选哪个？", options, false);

        DecisionRegistry.ResolveOutcome outcome =
                registry.resolve(reg.token(), "plan_a", null, "user-1", "msg-other-gen");

        assertThat(outcome).isEqualTo(DecisionRegistry.ResolveOutcome.NOT_FOUND);
        assertThat(reg.future().isDone()).isFalse();
        assertThat(registry.hasAwaiting("msg-owner")).isTrue();
    }

    @Test
    void resolve_expectedMessageIdMatch_completesFuture() throws Exception {
        List<DecisionOption> options = sampleOptions(false);
        DecisionRegistry.Registration reg =
                registry.register("msg-match", "user-1", "选哪个？", options, false);

        DecisionRegistry.ResolveOutcome outcome =
                registry.resolve(reg.token(), "plan_a", null, "user-1", "msg-match");

        assertThat(outcome).isEqualTo(DecisionRegistry.ResolveOutcome.ACCEPTED);
        assertThat(reg.future().get(1, TimeUnit.SECONDS).choice()).isEqualTo("plan_a");
    }

    @Test
    void awaitDecision_timeout_returnsTimeoutChoice() throws Exception {
        executionProperties.getReact().getDecision().setTimeoutSec(0);
        List<DecisionOption> options = sampleOptions(false);
        DecisionRegistry.Registration reg =
                registry.register("msg-6", "user-1", "选哪个？", options, false);

        DecisionResult result = registry.awaitDecision(reg);

        assertThat(result.choice()).isEqualTo("__timeout__");
        assertThat(result.customInput()).isNull();
        assertThat(registry.hasAwaiting("msg-6")).isFalse();
    }

    @Test
    void awaitDecision_afterCancel_returnsCancelledChoice() throws Exception {
        List<DecisionOption> options = sampleOptions(false);
        DecisionRegistry.Registration reg =
                registry.register("msg-7", "user-1", "选哪个？", options, false);
        registry.cancelWaitersForMessage("msg-7");

        DecisionResult result = registry.awaitDecision(reg);

        assertThat(result.choice()).isEqualTo("__cancelled__");
        assertThat(result.customInput()).isNull();
        assertThat(registry.hasAwaiting("msg-7")).isFalse();
    }

    private static List<DecisionOption> sampleOptions(boolean requireInput) {
        return List.of(
                new DecisionOption("plan_a", "方案A", "快", requireInput),
                new DecisionOption("plan_b", "方案B", "全", false));
    }
}
