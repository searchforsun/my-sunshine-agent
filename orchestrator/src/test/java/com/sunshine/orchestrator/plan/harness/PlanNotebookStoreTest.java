package com.sunshine.orchestrator.plan.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanNotebookStoreTest {
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> values;
    AgentExecutionProperties props = new AgentExecutionProperties();

    @BeforeEach
    void bind() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void saveLoadRoundTrip() throws Exception {
        PlanNotebookStoreImpl store = new PlanNotebookStoreImpl(redis, props, new ObjectMapper());
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setSessionId("s1");
        store.save(nb);
        verify(values).set(eq("sunshine:plan:notebook:s1"), anyString(), eq(Duration.ofSeconds(604_800)));
        when(values.get("sunshine:plan:notebook:s1")).thenReturn(new ObjectMapper().writeValueAsString(nb));
        assertThat(store.load("s1")).isPresent();
    }

    @Test
    void loadMarksInProgressTasksFailed() throws Exception {
        PlanNotebook nb = PlanNotebook.create("g", "q", "task", 12, 24);
        nb.setSessionId("s1");
        nb.getTaskQueue().add(new TaskItem("t1", "x", "in_progress", List.of(), "", "", ""));
        when(values.get(anyString())).thenReturn(new ObjectMapper().writeValueAsString(nb));
        PlanNotebook loaded = new PlanNotebookStoreImpl(redis, props, new ObjectMapper()).load("s1").orElseThrow();
        assertThat(loaded.getTaskQueue().peekFirst().status()).isEqualTo("fail");
    }
}
