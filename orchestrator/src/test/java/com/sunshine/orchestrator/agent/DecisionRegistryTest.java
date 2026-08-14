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
    void resolve_accepts_multi_select_and_custom() throws Exception {
        var questions = List.of(new DecisionQuestion(
                "q2", "关注？",
                List.of(new DecisionOption("perf", "性能"), new DecisionOption("ux", "体验")),
                true));
        DecisionRegistry.Registration reg = registry.register("msg-ms", "user-1", "T", questions);
        var answers = List.of(new DecisionAnswer(
                "q2", List.of("perf", DecisionOption.CUSTOM_ID), "还要安全"));

        assertThat(registry.resolve(reg.token(), answers, "user-1", "msg-ms"))
                .isEqualTo(DecisionRegistry.ResolveOutcome.ACCEPTED);
        DecisionResult r = reg.future().get(1, TimeUnit.SECONDS);
        assertThat(r.outcome()).isEqualTo("answered");
        assertThat(r.title()).isEqualTo("T");
        assertThat(r.answers().get(0).selectedOptionIds()).containsExactly("perf", "__custom__");
        assertThat(r.answers().get(0).customInput()).isEqualTo("还要安全");
        assertThat(registry.hasAwaiting("msg-ms")).isFalse();
    }

    @Test
    void resolve_clearsCustomInput_whenSelectedLacksCustomId() throws Exception {
        var questions = List.of(new DecisionQuestion(
                "q1", "模式？",
                List.of(new DecisionOption("a", "A"), new DecisionOption("b", "B")),
                false));
        DecisionRegistry.Registration reg = registry.register("msg-strip-custom", "user-1", "T", questions);
        var answers = List.of(new DecisionAnswer("q1", List.of("a"), "脏手写"));

        assertThat(registry.resolve(reg.token(), answers, "user-1", "msg-strip-custom"))
                .isEqualTo(DecisionRegistry.ResolveOutcome.ACCEPTED);
        DecisionResult r = reg.future().get(1, TimeUnit.SECONDS);
        assertThat(r.answers().get(0).selectedOptionIds()).containsExactly("a");
        assertThat(r.answers().get(0).customInput()).isNull();
    }

    @Test
    void resolve_rejects_missing_question() {
        var questions = List.of(
                new DecisionQuestion("q1", "模式？",
                        List.of(new DecisionOption("a", "A"), new DecisionOption("b", "B")), false),
                new DecisionQuestion("q2", "关注？",
                        List.of(new DecisionOption("perf", "性能"), new DecisionOption("ux", "体验")), true));
        DecisionRegistry.Registration reg = registry.register("msg-partial", "user-1", "T", questions);
        var answers = List.of(new DecisionAnswer("q1", List.of("a"), null));

        assertThat(registry.resolve(reg.token(), answers, "user-1", "msg-partial"))
                .isEqualTo(DecisionRegistry.ResolveOutcome.INVALID_ANSWERS);
        assertThat(reg.future().isDone()).isFalse();
        assertThat(registry.hasAwaiting("msg-partial")).isTrue();
    }

    @Test
    void resolve_rejects_single_select_two_ids() {
        var questions = List.of(new DecisionQuestion(
                "q1", "模式？",
                List.of(new DecisionOption("a", "A"), new DecisionOption("b", "B")),
                false));
        DecisionRegistry.Registration reg = registry.register("msg-single", "user-1", "T", questions);
        var answers = List.of(new DecisionAnswer("q1", List.of("a", "b"), null));

        assertThat(registry.resolve(reg.token(), answers, "user-1", "msg-single"))
                .isEqualTo(DecisionRegistry.ResolveOutcome.INVALID_CHOICE);
        assertThat(reg.future().isDone()).isFalse();
        assertThat(registry.hasAwaiting("msg-single")).isTrue();
    }

    @Test
    void resolve_customWithoutInput_returnsInputRequired() {
        var questions = List.of(new DecisionQuestion(
                "q1", "模式？",
                List.of(new DecisionOption("a", "A"), new DecisionOption("b", "B")),
                false));
        DecisionRegistry.Registration reg = registry.register("msg-custom", "user-1", "T", questions);
        var answers = List.of(new DecisionAnswer("q1", List.of(DecisionOption.CUSTOM_ID), "  "));

        assertThat(registry.resolve(reg.token(), answers, "user-1", "msg-custom"))
                .isEqualTo(DecisionRegistry.ResolveOutcome.INPUT_REQUIRED);
        assertThat(reg.future().isDone()).isFalse();
        assertThat(registry.hasAwaiting("msg-custom")).isTrue();
    }

    @Test
    void resolve_unknownOptionId_returnsInvalidChoice() {
        var questions = List.of(new DecisionQuestion(
                "q1", "模式？",
                List.of(new DecisionOption("a", "A"), new DecisionOption("b", "B")),
                false));
        DecisionRegistry.Registration reg = registry.register("msg-bad", "user-1", "T", questions);
        var answers = List.of(new DecisionAnswer("q1", List.of("not_an_option"), null));

        assertThat(registry.resolve(reg.token(), answers, "user-1", "msg-bad"))
                .isEqualTo(DecisionRegistry.ResolveOutcome.INVALID_CHOICE);
        assertThat(reg.future().isDone()).isFalse();
    }

    @Test
    void register_secondAwaitingOnSameMessage_rejected() {
        List<DecisionQuestion> questions = sampleQuestions();
        registry.register("msg-1", "user-1", "选哪个？", questions);
        assertThat(registry.hasAwaiting("msg-1")).isTrue();

        assertThatThrownBy(() ->
                registry.register("msg-1", "user-1", "再问一次？", questions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting");
    }

    @Test
    void register_concurrentSameMessage_onlyOneSucceeds() throws Exception {
        List<DecisionQuestion> questions = sampleQuestions();
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
                    registry.register("msg-race", "user-1", "选哪个？", questions);
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
    void cancelWaitersForMessage_cancelsFuture() {
        DecisionRegistry.Registration reg =
                registry.register("msg-4", "user-1", "选哪个？", sampleQuestions());

        registry.cancelWaitersForMessage("msg-4");

        assertThat(reg.future().isCancelled()).isTrue();
        assertThat(registry.hasAwaiting("msg-4")).isFalse();
    }

    @Test
    void register_stripsMessageId_soWhitespaceCannotBypassD15() {
        List<DecisionQuestion> questions = sampleQuestions();
        registry.register("  msg-d15  ", "user-1", "选哪个？", questions);

        assertThat(registry.hasAwaiting("msg-d15")).isTrue();
        assertThatThrownBy(() ->
                registry.register("msg-d15", "user-1", "再问？", questions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("awaiting");

        registry.cancelWaitersForMessage("  msg-d15  ");
        assertThat(registry.hasAwaiting("msg-d15")).isFalse();
    }

    @Test
    void resolve_messageIdMismatch_returnsNotFound_andDoesNotComplete() {
        DecisionRegistry.Registration reg =
                registry.register("msg-owner", "user-1", "选哪个？", sampleQuestions());
        var answers = List.of(new DecisionAnswer("q1", List.of("plan_a"), null));

        DecisionRegistry.ResolveOutcome outcome =
                registry.resolve(reg.token(), answers, "user-1", "msg-other-gen");

        assertThat(outcome).isEqualTo(DecisionRegistry.ResolveOutcome.NOT_FOUND);
        assertThat(reg.future().isDone()).isFalse();
        assertThat(registry.hasAwaiting("msg-owner")).isTrue();
    }

    @Test
    void resolve_expectedMessageIdMatch_completesFuture() throws Exception {
        DecisionRegistry.Registration reg =
                registry.register("msg-match", "user-1", "选哪个？", sampleQuestions());
        var answers = List.of(new DecisionAnswer("q1", List.of("plan_a"), null));

        DecisionRegistry.ResolveOutcome outcome =
                registry.resolve(reg.token(), answers, "user-1", "msg-match");

        assertThat(outcome).isEqualTo(DecisionRegistry.ResolveOutcome.ACCEPTED);
        assertThat(reg.future().get(1, TimeUnit.SECONDS).outcome()).isEqualTo("answered");
    }

    @Test
    void awaitDecision_timeout_returnsTimeoutOutcome() throws Exception {
        // timeoutSec<=0 现为无限等待语义，验证带超时路径用小正值
        executionProperties.getReact().getDecision().setTimeoutSec(1);
        DecisionRegistry.Registration reg =
                registry.register("msg-6", "user-1", "选哪个？", sampleQuestions());

        DecisionResult result = registry.awaitDecision(reg);

        assertThat(result.outcome()).isEqualTo("timeout");
        assertThat(result.title()).isEqualTo("选哪个？");
        assertThat(result.answers()).isEmpty();
        assertThat(registry.hasAwaiting("msg-6")).isFalse();
    }

    @Test
    void awaitDecision_afterCancel_returnsCancelledOutcome() throws Exception {
        DecisionRegistry.Registration reg =
                registry.register("msg-7", "user-1", "选哪个？", sampleQuestions());
        registry.cancelWaitersForMessage("msg-7");

        DecisionResult result = registry.awaitDecision(reg);

        assertThat(result.outcome()).isEqualTo("cancelled");
        assertThat(result.title()).isEqualTo("选哪个？");
        assertThat(result.answers()).isEmpty();
        assertThat(registry.hasAwaiting("msg-7")).isFalse();
    }

    @Test
    void skip_completesFutureWithSkippedOutcome() throws Exception {
        DecisionRegistry.Registration reg =
                registry.register("msg-skip", "user-1", "选哪个？", sampleQuestions());

        DecisionRegistry.ResolveOutcome outcome =
                registry.skip(reg.token(), "user-1", "msg-skip");

        assertThat(outcome).isEqualTo(DecisionRegistry.ResolveOutcome.ACCEPTED);
        DecisionResult result = reg.future().get(1, TimeUnit.SECONDS);
        assertThat(result.outcome()).isEqualTo("skipped");
        assertThat(result.answers()).isEmpty();
        assertThat(registry.hasAwaiting("msg-skip")).isFalse();
    }

    private static List<DecisionQuestion> sampleQuestions() {
        return List.of(new DecisionQuestion(
                "q1",
                "选哪个？",
                List.of(new DecisionOption("plan_a", "方案A"), new DecisionOption("plan_b", "方案B")),
                false));
    }
}
