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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    private String extractToken() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(flushScheduler).metaConfirmation(
                anyString(), anyString(), anyString(), captor.capture(), anyLong());
        return captor.getValue();
    }
}
