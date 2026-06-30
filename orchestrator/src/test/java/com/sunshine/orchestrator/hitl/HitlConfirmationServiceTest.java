package com.sunshine.orchestrator.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.generation.GenerationJob;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HitlConfirmationServiceTest {

    @Mock
    private ToolCatalogService toolCatalogService;
    @Mock
    private GenerationRegistry generationRegistry;
    @Mock
    private GenerationFlushScheduler flushScheduler;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private GenerationJob generationJob;

    private AgentHitlProperties properties;
    private HitlConfirmationService service;

    @BeforeEach
    void setUp() {
        properties = new AgentHitlProperties();
        properties.setEnabled(true);
        properties.setTimeoutSec(5);
        lenient().when(generationJob.getGenerationId()).thenReturn("gen-test");
        service = new HitlConfirmationService(
                properties,
                toolCatalogService,
                generationRegistry,
                flushScheduler,
                redis,
                new ObjectMapper());
        StepEventBridge.bindHitl("msg-1", true);
    }

    @AfterEach
    void tearDown() {
        StepEventBridge.clear("msg-1");
    }

    @Test
    void shouldConfirmForBridge_whenHitlEnabledForBridge() {
        when(toolCatalogService.isWriteTool("approve_oa_task")).thenReturn(true);
        assertThat(service.shouldConfirmForBridge("approve_oa_task", "msg-1")).isTrue();
        assertThat(service.shouldConfirmForBridge("approve_oa_task", null)).isFalse();
    }

    @Test
    void confirm_resolvesAwait() throws Exception {
        when(toolCatalogService.displayName("approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.awaitConfirmation("msg-1", "approve_oa_task", Map.of("taskId", "T1001")));

        Thread.sleep(200);
        assertThat(service.confirm(extractToken(), true)).isTrue();
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
        verify(generationJob).emitOutbound("{\"type\":\"confirmation\"}");
    }

    @Test
    void resumeAwaitingFromCheckpoint_reRegistersToken() throws Exception {
        when(toolCatalogService.displayName("approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        com.sunshine.orchestrator.processing.ProcessingTimelineSession session =
                com.sunshine.orchestrator.processing.ProcessingTimelineSupport.newSession();
        WorkflowHitlScope.Binding hitl = new WorkflowHitlScope.Binding(
                session, "node-approve", "msg-1");
        com.sunshine.orchestrator.plan.PendingInteraction pending = new com.sunshine.orchestrator.plan.PendingInteraction(
                "hitl", "approve", null, "approve_oa_task", "taskId=T1001", null);

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.resumeAwaitingFromCheckpoint(hitl, "msg-1", pending, "approve_oa_task"));

        Thread.sleep(200);
        assertThat(service.confirm(extractToken(), true)).isTrue();
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void resumeReactAwaiting_reRegistersTokenViaGenerationJob() throws Exception {
        when(toolCatalogService.displayName("approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        com.sunshine.orchestrator.agent.ProcessingStep toolStep = pausedReactHitlToolStep();
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.resumeReactAwaiting(toolStep.id(), "msg-1", toolStep));

        Thread.sleep(200);
        assertThat(service.confirm(extractToken(), true)).isTrue();
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
        verify(generationJob).emitOutbound("{\"type\":\"confirmation\"}");
    }

    private static com.sunshine.orchestrator.agent.ProcessingStep pausedReactHitlToolStep() {
        com.sunshine.orchestrator.processing.HitlStepMeta hitl = com.sunshine.orchestrator.processing.HitlStepMeta.awaiting(
                "old-token", "审批 OA 待办", "taskId=T1001", System.currentTimeMillis() + 60_000);
        com.sunshine.orchestrator.processing.StepMetadata meta = com.sunshine.orchestrator.processing.StepMetadata.withHitl(
                null, hitl);
        return new com.sunshine.orchestrator.agent.ProcessingStep(
                "tool-approve_oa_task@1",
                "tool",
                "paused",
                new com.sunshine.orchestrator.processing.StepSummary(null, "已暂停", "已暂停"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                null,
                2L,
                null,
                meta,
                null,
                null);
    }

    @Test
    void cancelWaitersForMessage_interruptsWithoutUserDeny() throws Exception {
        when(toolCatalogService.displayName("approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.awaitConfirmation("msg-1", "approve_oa_task", Map.of("taskId", "T1001")));

        Thread.sleep(200);
        service.cancelWaitersForMessage("msg-1");
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(HitlWaitInterruptedException.class);
    }

    @Test
    void awaitConfirmation_truncatesLongParamValuesInSummary() throws Exception {
        when(toolCatalogService.displayName("approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        String longReason = "x".repeat(150);
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.awaitConfirmation("msg-1", "approve_oa_task", Map.of("taskId", longReason)));

        Thread.sleep(200);
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).metaConfirmation(
                anyString(), anyString(), summaryCaptor.capture(), anyString(), anyLong());
        assertThat(summaryCaptor.getValue()).startsWith("taskId=");
        assertThat(summaryCaptor.getValue()).hasSizeLessThanOrEqualTo("taskId=".length() + 120 + 1);
        assertThat(summaryCaptor.getValue()).endsWith("…");

        service.confirm(extractToken(), true);
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
    }

    private String extractToken() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).metaConfirmation(
                anyString(), anyString(), anyString(), captor.capture(), anyLong());
        return captor.getValue();
    }
}
