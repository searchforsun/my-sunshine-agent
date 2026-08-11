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

import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private static List<DecisionOption> sampleOptions(boolean requireInput) {
        return List.of(
                new DecisionOption("plan_a", "方案A", "快", requireInput),
                new DecisionOption("plan_b", "方案B", "全", false));
    }
}
