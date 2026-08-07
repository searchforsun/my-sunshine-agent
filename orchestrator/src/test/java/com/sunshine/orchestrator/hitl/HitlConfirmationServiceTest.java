package com.sunshine.orchestrator.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private com.sunshine.orchestrator.generation.GenerationJob generationJob;
    @Mock
    private com.sunshine.orchestrator.client.SandboxClient sandboxClient;

    private AgentHitlProperties properties;
    private AgentSandboxProperties sandboxProperties;
    private HitlConfirmationService service;

    @BeforeEach
    void setUp() {
        properties = new AgentHitlProperties();
        properties.setEnabled(true);
        properties.setTimeoutSec(5);
        sandboxProperties = new AgentSandboxProperties();
        lenient().when(generationJob.getGenerationId()).thenReturn("gen-test");
        HitlTokenRegistry tokenRegistry = new HitlTokenRegistry(properties, redis, new ObjectMapper());
        HitlTimelineBridge timelineBridge = new HitlTimelineBridge(
                generationRegistry, flushScheduler, toolCatalogService);
        service = new HitlConfirmationService(
                properties, toolCatalogService, tokenRegistry, timelineBridge, new HitlWriteToolSerialGate(),
                sandboxClient, sandboxProperties);
        com.sunshine.orchestrator.processing.TimelineLabelTestSupport.bindDefaults();
        com.sunshine.orchestrator.processing.ToolNodeLabels.bind(
                new com.sunshine.orchestrator.processing.ToolNodeLabelService(
                        com.sunshine.orchestrator.prompt.TimelinePromptCatalog.withDefaults(), toolCatalogService));
        com.sunshine.orchestrator.processing.StepLabels.bind(toolCatalogService);
        com.sunshine.orchestrator.agent.StepEventBridge.bindHitl("msg-1", true);
    }

    @AfterEach
    void tearDown() {
        com.sunshine.orchestrator.processing.StepLabels.bind(null);
        com.sunshine.orchestrator.processing.TimelineLabelTestSupport.unbind();
        com.sunshine.orchestrator.agent.StepEventBridge.clear("msg-1");
    }

    @Test
    void shouldConfirmForBridge_whenHitlEnabledForBridge() {
        when(toolCatalogService.requiresConfirmation("sdk__sunshine-oa__approve_oa_task")).thenReturn(true);
        assertThat(service.shouldConfirmForBridge("sdk__sunshine-oa__approve_oa_task", "msg-1")).isTrue();
        assertThat(service.shouldConfirmForBridge("sdk__sunshine-oa__approve_oa_task", null)).isFalse();
    }

    @Test
    void confirm_resolvesAwait() throws Exception {
        when(toolCatalogService.displayName("sdk__sunshine-oa__approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.awaitConfirmation("msg-1", "sdk__sunshine-oa__approve_oa_task", Map.of("taskId", "T1001")));

        Thread.sleep(200);
        assertThat(service.confirm(extractToken(), true)).isTrue();
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
        verify(generationJob).emitOutbound("{\"type\":\"confirmation\"}");
    }

    @Test
    void resumeAwaitingFromCheckpoint_reRegistersToken() throws Exception {
        when(toolCatalogService.displayName("sdk__sunshine-oa__approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        com.sunshine.orchestrator.processing.ProcessingTimelineSession session =
                com.sunshine.orchestrator.processing.ProcessingTimelineSupport.newSession();
        WorkflowHitlScope.Binding hitl = new WorkflowHitlScope.Binding(
                session, "node-approve", "msg-1");
        com.sunshine.orchestrator.plan.PendingInteraction pending = new com.sunshine.orchestrator.plan.PendingInteraction(
                "hitl", "approve", null, "sdk__sunshine-oa__approve_oa_task", "taskId=T1001", null);

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.resumeAwaitingFromCheckpoint(hitl, "msg-1", pending, "sdk__sunshine-oa__approve_oa_task"));

        Thread.sleep(200);
        assertThat(service.confirm(extractToken(), true)).isTrue();
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void resumeReactAwaiting_reRegistersTokenViaGenerationJob() throws Exception {
        when(toolCatalogService.displayName("sdk__sunshine-oa__approve_oa_task")).thenReturn("审批 OA 待办");
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
                "tool-sdk__sunshine-oa__approve_oa_task@1",
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
                null,
                null);
    }

    @Test
    void cancelWaitersForMessage_interruptsWithoutUserDeny() throws Exception {
        when(toolCatalogService.displayName("sdk__sunshine-oa__approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.awaitConfirmation("msg-1", "sdk__sunshine-oa__approve_oa_task", Map.of("taskId", "T1001")));

        Thread.sleep(200);
        service.cancelWaitersForMessage("msg-1");
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(HitlWaitInterruptedException.class);
    }

    @Test
    void awaitConfirmation_truncatesLongParamValuesInSummary() throws Exception {
        when(toolCatalogService.displayName("sdk__sunshine-oa__approve_oa_task")).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        String longReason = "x".repeat(150);
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.awaitConfirmation("msg-1", "sdk__sunshine-oa__approve_oa_task", Map.of("taskId", longReason)));

        Thread.sleep(200);
        org.mockito.ArgumentCaptor<String> summaryCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).metaConfirmation(
                anyString(), anyString(), summaryCaptor.capture(), anyString(), anyLong());
        assertThat(summaryCaptor.getValue()).startsWith("taskId=");
        assertThat(summaryCaptor.getValue()).hasSizeLessThanOrEqualTo("taskId=".length() + 120 + 1);
        assertThat(summaryCaptor.getValue()).endsWith("…");

        service.confirm(extractToken(), true);
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void awaitConfirmation_withStepId_progressesExactStep() throws Exception {
        when(toolCatalogService.displayName(anyString())).thenReturn("审批 OA 待办");
        when(generationRegistry.findByMessageId("msg-1")).thenReturn(Optional.of(generationJob));
        when(flushScheduler.metaConfirmation(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("{\"type\":\"confirmation\"}");
        when(redis.opsForValue()).thenReturn(valueOps);

        // 一轮多 tool_calls：currentToolStepId 指向其它工具，HITL 必须精确挂到 exec 步
        ProcessingTimelineSession session =
                com.sunshine.orchestrator.processing.ProcessingTimelineSupport.newSession();
        session.bindTraceMessageId("msg-1");
        String execStepId = session.beginToolStep("tool-sandbox__exec", "tool");
        String globStepId = session.beginToolStep("tool-sandbox__glob", "tool");
        assertThat(session.currentToolStepId()).isEqualTo(globStepId);

        // 用 toolUse→stepId 精确绑定（模拟 ProcessingStepMiddleware.beginToolStep 的 bindToolUseStep）
        com.sunshine.orchestrator.agent.StepEventBridge.bind("msg-1", session,
                new ConcurrentLinkedQueue<>());
        com.sunshine.orchestrator.agent.StepEventBridge.bindToolUseStep("toolUse-exec", execStepId);

        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> service.awaitConfirmation(
                        "msg-1", "msg-1", "sdk__sunshine-oa__approve_oa_task",
                        Map.of("taskId", "T1001"), execStepId));

        Thread.sleep(200);
        assertThat(service.confirm(extractToken(), true)).isTrue();
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();

        // exec 步收到 HITL metadata（approved），glob 步不被误挂
        ProcessingStep execStep = session.snapshot().stream()
                .filter(s -> execStepId.equals(s.id()))
                .findFirst()
                .orElseThrow();
        assertThat(execStep.metadata()).isNotNull();
        assertThat(execStep.metadata().hitl()).isNotNull();
        assertThat(execStep.metadata().hitl().status()).isEqualTo("approved");
        ProcessingStep globStep = session.snapshot().stream()
                .filter(s -> globStepId.equals(s.id()))
                .findFirst()
                .orElseThrow();
        assertThat(globStep.metadata()).isNull();
    }

    private String extractToken() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).metaConfirmation(
                anyString(), anyString(), anyString(), captor.capture(), anyLong());
        return captor.getValue();
    }
}
