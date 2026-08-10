package com.sunshine.llm.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.adapter.LlmAdapter;
import com.sunshine.llm.filter.NormalizeFilter;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.registry.GatewayModelCatalog;
import com.sunshine.llm.registry.ModelCapabilities;
import com.sunshine.llm.registry.ModelCryptoService;
import com.sunshine.llm.registry.ModelDefinitionView;
import com.sunshine.llm.registry.ModelProviderView;
import com.sunshine.llm.registry.ModelRegistryCache;
import com.sunshine.llm.registry.ModelSceneView;
import com.sunshine.llm.config.LlmWebClientFactory;
import com.sunshine.llm.trace.LlmIoTracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRouterTest {

    private AdapterCircuitBreaker circuitBreaker;
    private ModelRegistryCache registryCache;
    private NormalizeFilter normalizeFilter;

    @BeforeEach
    void setUp() {
        circuitBreaker = new AdapterCircuitBreaker();
        ObjectMapper mapper = new ObjectMapper();
        normalizeFilter = new NormalizeFilter(mapper);
        ModelCryptoService crypto = new ModelCryptoService("sunshine-model-aes-key-32b!!");
        LlmWebClientFactory factory = new LlmWebClientFactory();
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(WebClient.builder().build());
        registryCache = new ModelRegistryCache(
                mapper, crypto, factory, builder,
                "http://sunshine-resource-manager", "default");
        registryCache.replaceSnapshotForTest(GatewayModelCatalog.builder()
                .providers(List.of(
                        ModelProviderView.builder()
                                .providerKey("deepseek").baseUrl("https://api.deepseek.com")
                                .pathPrefix("/v1").apiKeyEnc(crypto.encrypt("k")).enabled(true).build(),
                        ModelProviderView.builder()
                                .providerKey("qwen").baseUrl("https://dashscope.example/v1")
                                .pathPrefix("").apiKeyEnc(crypto.encrypt("k")).enabled(true).build()))
                .definitions(List.of(
                        ModelDefinitionView.builder()
                                .providerKey("deepseek").modelName("deepseek-v4-pro")
                                .enabled(true)
                                .capabilities(ModelCapabilities.builder()
                                        .reasoning(true).toolCall(true).build())
                                .build(),
                        ModelDefinitionView.builder()
                                .providerKey("qwen").modelName("qwen-plus")
                                .enabled(true)
                                .capabilities(ModelCapabilities.builder().toolCall(true).build())
                                .build()))
                .scenes(List.of(
                        ModelSceneView.builder()
                                .sceneKey("chat")
                                .primaryModel("deepseek-v4-pro")
                                .fallbackModel("qwen-plus")
                                .enabled(true)
                                .build()))
                .build());
    }

    private ModelRouter newRouter(LlmAdapter... adapters) {
        return new ModelRouter(
                List.of(adapters),
                registryCache,
                normalizeFilter,
                circuitBreaker,
                new LlmIoTracer(new ObjectMapper(), false));
    }

    @Test
    @DisplayName("route(qwen-plus) 选中支持该模型的 Adapter")
    void route_qwenPlus_selectsAdapter() {
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
        LlmAdapter adapter = mock(LlmAdapter.class);
        when(adapter.supports("unknown-model")).thenReturn(false);

        ModelRouter router = newRouter(adapter);

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("unknown-model");

        assertThatThrownBy(() -> router.route(request).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown-model");
    }

    @Test
    @DisplayName("主模型失败时按场景绑定降级")
    void route_primaryFails_fallbackViaScene() {
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
    @DisplayName("body fallback_model 优先于场景绑定")
    void route_explicitFallbackModel_preferred() {
        LlmAdapter primary = mock(LlmAdapter.class);
        LlmAdapter fallback = mock(LlmAdapter.class);
        when(primary.supports("deepseek-v4-pro")).thenReturn(true);
        when(fallback.supports("qwen-plus")).thenReturn(true);
        when(primary.chat(any())).thenReturn(Mono.error(new RuntimeException("down")));
        when(fallback.chat(any())).thenReturn(Mono.just(new ChatCompletionResponse()));

        ModelRouter router = newRouter(primary, fallback);

        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("deepseek-v4-pro");
        request.setFallbackModel("qwen-plus");

        assertThat(router.route(request).block()).isNotNull();
        verify(fallback).chat(any());
    }

    @Test
    @DisplayName("stream 主模型失败时降级")
    void stream_primaryFails_fallback() {
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
