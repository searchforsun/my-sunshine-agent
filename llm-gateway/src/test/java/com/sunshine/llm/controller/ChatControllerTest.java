package com.sunshine.llm.controller;

import com.sunshine.llm.cache.SemanticCacheService;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.router.ModelRouter;
import com.sunshine.llm.usage.QuotaCheckClient;
import com.sunshine.llm.usage.TokenUsageCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ModelRouter router;

    @MockBean
    private SemanticCacheService cache;

    @MockBean
    private TokenUsageCollector usageCollector;

    @MockBean
    private QuotaCheckClient quotaCheckClient;

    @BeforeEach
    void setUp() {
        // 流式挂点需要可用的累计器；用 mock 避免真实依赖（publisher 等）
        when(usageCollector.newStreamAccumulator(any())).thenReturn(mock(TokenUsageCollector.StreamUsageAccumulator.class));
        // 配额默认放行（校验开关默认关；此处 mock 显式返回 ALLOWED）
        when(quotaCheckClient.check(any(), any())).thenReturn(new QuotaCheckClient.Outcome(true, "ok"));
    }

    @Test
    void chatCompletions_streamTrue_routesToStreamNotCache() {
        when(router.stream(any())).thenReturn(Flux.just(
                ServerSentEvent.builder("data: {\"choices\":[]}").build()));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue("""
                        {"model":"deepseek-v4-pro","stream":true,"messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);

        verify(router).stream(any());
        verify(cache, never()).get(any());
        verify(router, never()).route(any());
    }

    @Test
    void chatCompletions_streamFalse_usesCacheAndRoute() {
        ChatCompletionResponse response = ChatCompletionResponse.builder().model("deepseek-v4-pro").build();
        when(cache.get(any())).thenReturn(Mono.empty());
        when(router.route(any())).thenReturn(Mono.just(response));
        when(cache.put(any(), any())).thenReturn(Mono.empty());

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"deepseek-v4-pro","stream":false,"messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);

        verify(router).route(any());
        verify(router, never()).stream(any());
    }

    @Test
    void chatCompletions_quotaRejected_returns429() {
        when(quotaCheckClient.check(any(), any()))
                .thenReturn(new QuotaCheckClient.Outcome(false, "quota_exceeded"));

        webTestClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"model":"deepseek-v4-pro","stream":false,"messages":[{"role":"user","content":"hi"}]}
                        """)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("quota_exceeded")
                .jsonPath("$.error.type").isEqualTo("quota_error");

        verify(router, never()).route(any());
        verify(router, never()).stream(any());
    }

}
