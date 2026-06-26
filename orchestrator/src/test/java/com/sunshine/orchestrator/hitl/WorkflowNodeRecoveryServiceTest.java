package com.sunshine.orchestrator.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowNodeRecoveryServiceTest {

    @Mock
    private AgentHitlProperties properties;
    @Mock
    private GenerationRegistry generationRegistry;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private WorkflowNodeRecoveryService service;

    @Test
    void parseActionSupportsSkip() throws Exception {
        Method m = WorkflowNodeRecoveryService.class.getDeclaredMethod("parseAction", String.class);
        m.setAccessible(true);
        assertThat(m.invoke(service, "skip")).isEqualTo(WorkflowRecoveryAction.SKIP);
        assertThat(m.invoke(service, "SKIP")).isEqualTo(WorkflowRecoveryAction.SKIP);
        assertThat(m.invoke(service, "retry")).isEqualTo(WorkflowRecoveryAction.RETRY);
        assertThat(m.invoke(service, "terminate")).isEqualTo(WorkflowRecoveryAction.TERMINATE);
    }
}
