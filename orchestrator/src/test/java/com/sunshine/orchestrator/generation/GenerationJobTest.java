package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.memory.MemoryLifecycleService;
import com.sunshine.testsupport.EmbeddedRedisTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EmbeddedRedisTestConfig.class)
@EnableConfigurationProperties(GenerationProperties.class)
class GenerationJobTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String USER_ID = "alice";
    private static final String TENANT_ID = "default";
    private static final String INTENT = "chat";

    @Autowired
    private GenerationStreamService streamService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private GenerationProperties properties;

    private GenerationFlushScheduler flushScheduler;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", EmbeddedRedisTestConfig::redisHost);
        registry.add("spring.data.redis.port", EmbeddedRedisTestConfig::redisPort);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("agent.generation.ttl-sec", () -> 3600);
        registry.add("agent.generation.orphan-timeout-sec", () -> 120);
        registry.add("agent.generation.max-buffer-chunks", () -> 10000);
        registry.add("agent.generation.reconnect-block-ms", () -> 100);
        registry.add("agent.generation.flush-interval-ms", () -> 50);
    }

    @BeforeEach
    void setUp() {
        flushScheduler = mock(GenerationFlushScheduler.class);
        when(flushScheduler.metaReasoning(anyString()))
                .thenAnswer(inv -> "{\"type\":\"reasoning\",\"text\":\"" + inv.getArgument(0) + "\"}");
        when(flushScheduler.metaContent(anyString()))
                .thenAnswer(inv -> "{\"type\":\"content\",\"text\":\"" + inv.getArgument(0) + "\"}");
        when(flushScheduler.metaStep(org.mockito.ArgumentMatchers.any()))
                .thenReturn("{\"type\":\"step\"}");
        when(flushScheduler.metaStepDelta(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "{\"type\":\"step_delta\",\"stepId\":\"" + inv.getArgument(0)
                        + "\",\"channel\":\"" + inv.getArgument(1)
                        + "\",\"text\":\"" + inv.getArgument(2) + "\"}");
        Set<String> keys = redis.keys("sunshine:gen:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("start 消费 llmFlux → Redis 写入 seq 1,2,3 且 status=COMPLETED")
    void start_writesChunksAndCompletes() throws Exception {
        String generationId = streamService.createGeneration(
                CONVERSATION_ID, MESSAGE_ID, USER_ID, TENANT_ID, INTENT);

        GenerationJob job = new GenerationJob(
                generationId, MESSAGE_ID, CONVERSATION_ID, USER_ID, TENANT_ID, INTENT, "hello",
                streamService, properties, flushScheduler, null);

        StringBuilder buffer = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        job.start(
                Flux.just(StreamToken.content("a"), StreamToken.content("b"), StreamToken.content("c")),
                buffer,
                content -> { },
                done::countDown,
                errorRef::set
        );

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errorRef.get()).isNull();
        assertThat(buffer.toString()).isEqualTo("abc");

        List<StreamEvent> events = streamService.readFrom(generationId, 0, 10);
        assertThat(events).hasSize(5);
        assertThat(events.get(0).text()).isEqualTo("{\"type\":\"step\"}");
        assertThat(events.get(1).text()).isEqualTo("{\"type\":\"content\",\"text\":\"a\"}");
        assertThat(events.get(2).text()).isEqualTo("{\"type\":\"content\",\"text\":\"b\"}");
        assertThat(events.get(3).text()).isEqualTo("{\"type\":\"content\",\"text\":\"c\"}");
        assertThat(events.get(4).text()).isEqualTo("{\"type\":\"step\"}");

        GenerationMeta meta = streamService.getMeta(generationId).orElseThrow();
        assertThat(meta.status()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(meta.lastSeq()).isEqualTo(5);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stepsCaptor = ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).commitFinal(
                eq(MESSAGE_ID), contentCaptor.capture(), eq(""), eq(MessageStatus.COMPLETED), stepsCaptor.capture());
        assertThat(contentCaptor.getValue()).isEqualTo("abc");
        assertThat(stepsCaptor.getValue()).contains("generate");
    }

    @Test
    @DisplayName("完成后刷新 STM 记忆")
    void start_refreshesMemoryAfterComplete() throws Exception {
        String generationId = streamService.createGeneration(
                CONVERSATION_ID, MESSAGE_ID, USER_ID, TENANT_ID, INTENT);
        MemoryLifecycleService memoryLifecycleService = mock(MemoryLifecycleService.class);

        GenerationJob job = new GenerationJob(
                generationId, MESSAGE_ID, CONVERSATION_ID, USER_ID, TENANT_ID, INTENT, "hello",
                streamService, properties, flushScheduler, memoryLifecycleService);

        CountDownLatch done = new CountDownLatch(1);
        job.start(
                Flux.just(StreamToken.content("answer")),
                new StringBuilder(),
                content -> { },
                done::countDown,
                error -> { }
        );

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(memoryLifecycleService).onAssistantCompleted(
                MESSAGE_ID, USER_ID, TENANT_ID, MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("reasoning token 写入 Redis 并在 commitFinal 时落库")
    void start_persistsReasoningOnComplete() throws Exception {
        String generationId = streamService.createGeneration(
                CONVERSATION_ID, MESSAGE_ID, USER_ID, TENANT_ID, INTENT);

        GenerationJob job = new GenerationJob(
                generationId, MESSAGE_ID, CONVERSATION_ID, USER_ID, TENANT_ID, INTENT, "hello",
                streamService, properties, flushScheduler, null);

        StringBuilder buffer = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);

        job.start(
                Flux.just(StreamToken.reasoning("think"), StreamToken.content("ok")),
                buffer,
                content -> { },
                done::countDown,
                error -> { }
        );

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(buffer.toString()).isEqualTo("ok");

        ArgumentCaptor<String> reasoningCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stepsCaptor = ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).commitFinal(
                eq(MESSAGE_ID), eq("ok"), reasoningCaptor.capture(), eq(MessageStatus.COMPLETED), stepsCaptor.capture());
        assertThat(reasoningCaptor.getValue()).isEqualTo("think");
        assertThat(stepsCaptor.getValue()).contains("think").contains("generate");
    }

    @Test
    @DisplayName("step token 在 commitFinal 时落库为 steps JSON")
    void start_persistsStepsOnComplete() throws Exception {
        String generationId = streamService.createGeneration(
                CONVERSATION_ID, MESSAGE_ID, USER_ID, TENANT_ID, INTENT);

        GenerationJob job = new GenerationJob(
                generationId, MESSAGE_ID, CONVERSATION_ID, USER_ID, TENANT_ID, INTENT, "hello",
                streamService, properties, flushScheduler, null);

        StringBuilder buffer = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);

        var session = com.sunshine.orchestrator.processing.ProcessingTimelineSupport.newSession();
        session.pending("intent", "intent");
        session.start("intent", "intent");
        session.complete("intent", "简单对话");
        ProcessingStep intentDone = session.snapshot().get(0);

        job.start(
                Flux.just(
                        StreamToken.step(intentDone),
                        StreamToken.content("ok")),
                buffer,
                content -> { },
                done::countDown,
                error -> { }
        );

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<String> stepsCaptor = ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).commitFinal(
                eq(MESSAGE_ID), eq("ok"), eq(""), eq(MessageStatus.COMPLETED), stepsCaptor.capture());
        String stepsJson = stepsCaptor.getValue();
        assertThat(stepsJson).contains("识别意图").contains("简单对话");
        assertThat(stepsJson).contains("lifecycle").contains("summary");
    }
}
