package com.sunshine.llm.router;

import com.sunshine.llm.adapter.LlmAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.config.ModelFallbackProperties;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.trace.LlmIoTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRouterTest {

    private ModelFallbackProperties fallbackProperties;
    private AdapterCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        fallbackProperties = new ModelFallbackProperties();
        fallbackProperties.setRoutes(Map.of(
                "deepseek-v4-pro", "qwen-plus",
                "deepseek-v4-flash", "qwen-plus"));
        circuitBreaker = new AdapterCircuitBreaker();
    }

    private ModelRouter newRouter(LlmAdapter... adapters) {
        return new ModelRouter(
                List.of(adapters),
                fallbackProperties,
                circuitBreaker,
                new LlmIoTracer(new ObjectMapper(), false));
    }

    @Test
    @DisplayName("route(qwen-plus) 选中 QwenAdapter")
    void route_qwenPlus_selectsQwenAdapter() {
        LlmAdapter deepseek = mock(LlmAdapter.class);
        LlmAdapter qwen = mock(LlmAdapter.class);
        when(deepseek.supports("qwen-plus")).thenReturn(false);
        when(qwen.supports("qwen-plus")).thenReturn(true);
        when(qwen.chat(any())).thenReturn(Mono.just(new ChatCompletionResponse()));

        ModelRouter router = newRouter(deepseek, qwen);

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("qwen-plus");

        router.route(request).block();

        verify(qwen).chat(any());
        verify(deepseek, never()).chat(any());
    }

    @Test
    @DisplayName("route(unknown) 抛出 IllegalArgumentException")
    void route_unknownModel_throws() {
        LlmAdapter deepseek = mock(LlmAdapter.class);
        LlmAdapter qwen = mock(LlmAdapter.class);
        when(deepseek.supports("unknown-model")).thenReturn(false);
        when(qwen.supports("unknown-model")).thenReturn(false);

        ModelRouter router = newRouter(deepseek, qwen);

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("unknown-model");

        assertThatThrownBy(() -> router.route(request).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-model");
    }

    @Test
    @DisplayName("主模型失败时自动降级到备用模型")
    void route_primaryFails_fallbackToQwen() {
        LlmAdapter deepseek = mock(LlmAdapter.class);
        LlmAdapter qwen = mock(LlmAdapter.class);
        when(deepseek.supports("deepseek-v4-pro")).thenReturn(true);
        when(qwen.supports("qwen-plus")).thenReturn(true);
        when(deepseek.chat(any())).thenReturn(Mono.error(new RuntimeException("upstream down")));
        when(qwen.chat(any())).thenReturn(Mono.just(new ChatCompletionResponse()));

        ModelRouter router = newRouter(deepseek, qwen);

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("deepseek-v4-pro");

        ChatCompletionResponse resp = router.route(request).block();
        assertThat(resp).isNotNull();
        verify(qwen).chat(any());
    }

    @Test
    @DisplayName("stream 主模型失败时降级")
    void stream_primaryFails_fallbackToQwen() {
        LlmAdapter deepseek = mock(LlmAdapter.class);
        LlmAdapter qwen = mock(LlmAdapter.class);
        when(deepseek.supports("deepseek-v4-pro")).thenReturn(true);
        when(qwen.supports("qwen-plus")).thenReturn(true);
        when(deepseek.stream(any())).thenReturn(Flux.error(new RuntimeException("stream fail")));
        when(qwen.stream(any())).thenReturn(Flux.just(
                ServerSentEvent.builder("chunk").build()));

        ModelRouter router = newRouter(deepseek, qwen);

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("deepseek-v4-pro");

        StepVerifier.create(router.stream(request))
                .expectNextCount(1)
                .verifyComplete();
        verify(qwen).stream(any());
    }
}
