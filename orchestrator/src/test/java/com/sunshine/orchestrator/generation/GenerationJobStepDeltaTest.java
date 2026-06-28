package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EmbeddedRedisTestConfig.class)
@EnableConfigurationProperties(GenerationProperties.class)
class GenerationJobStepDeltaTest {

    private static final String CONVERSATION_ID = "conv-delta";
    private static final String MESSAGE_ID = "msg-delta";
    private static final String USER_ID = "alice";
    private static final String TENANT_ID = "default";
    private static final String INTENT = "knowledge";

    @Autowired
    private GenerationStreamService streamService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private GenerationProperties properties;

    private GenerationFlushScheduler flushScheduler;
    private WorkflowPauseService workflowPauseService;
    private ExecutionPlanStore executionPlanStore;

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
        registry.add("agent.generation.max-chunk-chars", () -> 32);
    }

    @BeforeEach
    void setUp() {
        flushScheduler = mock(GenerationFlushScheduler.class);
        when(flushScheduler.metaContent(anyString(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenAnswer(inv -> "{\"type\":\"content\",\"text\":\"" + inv.getArgument(0) + "\"}");
        when(flushScheduler.metaStepDelta(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> "{\"type\":\"step_delta\",\"stepId\":\"" + inv.getArgument(0)
                        + "\",\"channel\":\"" + inv.getArgument(1)
                        + "\",\"text\":\"" + inv.getArgument(2) + "\"}");
        when(flushScheduler.metaStep(org.mockito.ArgumentMatchers.any()))
                .thenReturn("{\"type\":\"step\"}");
        Set<String> keys = redis.keys("sunshine:gen:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        workflowPauseService = mock(WorkflowPauseService.class);
        executionPlanStore = mock(ExecutionPlanStore.class);
    }

    @Test
    @DisplayName("step_delta 写入 Redis 并在 commitFinal 时 steps JSON 含 reasoning")
    void stepDelta_persistsReasoningInSteps() throws Exception {
        String generationId = streamService.createGeneration(
                CONVERSATION_ID, MESSAGE_ID, USER_ID, TENANT_ID, INTENT);

        GenerationJob job = new GenerationJob(
                generationId, MESSAGE_ID, CONVERSATION_ID, USER_ID, TENANT_ID, INTENT, "hello",
                streamService, properties, flushScheduler, null,
                workflowPauseService, executionPlanStore, new AgentPauseProperties(), null);

        StringBuilder buffer = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);

        job.start(
                Flux.just(
                        StreamToken.stepDelta("think", "reasoning", "思考"),
                        StreamToken.stepDelta("think", "reasoning", "过程"),
                        StreamToken.content("ok")),
                buffer,
                content -> { },
                done::countDown,
                error -> { }
        );

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        List<StreamEvent> events = streamService.readFrom(generationId, 0, 20);
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        assertThat(events.stream().map(StreamEvent::text).filter(t -> t.contains("step_delta")).toList())
                .anyMatch(t -> t.contains("思考"));
        assertThat(events.stream().map(StreamEvent::text).filter(t -> t.contains("step_delta")).toList())
                .anyMatch(t -> t.contains("过程"));

        ArgumentCaptor<String> reasoningCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stepsCaptor = ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).commitFinal(
                eq(MESSAGE_ID), eq("ok"), reasoningCaptor.capture(), eq(MessageStatus.COMPLETED), stepsCaptor.capture());
        assertThat(reasoningCaptor.getValue()).isEqualTo("思考过程");
        assertThat(stepsCaptor.getValue()).contains("think").contains("思考过程");
    }
}
