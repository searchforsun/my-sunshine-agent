package com.sunshine.llm.controller;

import com.sunshine.llm.cache.SemanticCacheService;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.router.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;

/**
 * 大模型网关控制器 — OpenAI 兼容 /v1/chat/completions
 * <p>
 * {@code stream:true} 时走 SSE 流式（AgentScope / OpenAI SDK 标准路径）；
 * 否则走非流式并启用语义缓存。
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatController {

    private final ModelRouter router;
    private final SemanticCacheService cache;

    /**
     * OpenAI 兼容入口：body {@code stream:true} → SSE；否则 JSON 完整响应。
     */
    @PostMapping("/chat/completions")
    public Object chatCompletions(@RequestBody ChatCompletionRequest request) {
        if (Boolean.TRUE.equals(request.getStream())) {
            log.info("[LLM-GW] 流式(OpenAI 兼容): model={}", request.getModel());
            // Servlet(MVC) 下 Object 返回 Flux 会被 Jackson 误序列化；SseEmitter 为 Tomcat 兼容路径
            return toSseEmitter(streamCompletion(request));
        }
        return chatCompletion(request);
    }

    /** 历史端点 — orchestrator {@link com.sunshine.orchestrator.client.LlmGatewayClient} 直连 */
    @PostMapping(value = "/chat/completions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamLegacy(@RequestBody ChatCompletionRequest request) {
        log.info("[LLM-GW] 流式(legacy /stream): model={}", request.getModel());
        return streamCompletion(request);
    }

    private Mono<ChatCompletionResponse> chatCompletion(ChatCompletionRequest request) {
        log.info("[LLM-GW] 非流式: model={}", request.getModel());
        return cache.get(request)
                .flatMap(cached -> {
                    log.info("[LLM-GW] 缓存命中");
                    return Mono.just(cached);
                })
                .switchIfEmpty(
                        router.route(request)
                                .flatMap(resp -> cache.put(request, resp).thenReturn(resp))
                );
    }

    private Flux<ServerSentEvent<String>> streamCompletion(ChatCompletionRequest request) {
        request.setStream(true);
        return router.stream(request);
    }

    private SseEmitter toSseEmitter(Flux<ServerSentEvent<String>> flux) {
        SseEmitter emitter = new SseEmitter(600_000L);
        flux.subscribe(
                event -> {
                    try {
                        SseEmitter.SseEventBuilder builder = SseEmitter.event();
                        if (event.id() != null) {
                            builder.id(event.id());
                        }
                        if (event.event() != null) {
                            builder.name(event.event());
                        }
                        if (event.data() != null) {
                            builder.data(event.data());
                        }
                        emitter.send(builder);
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );
        return emitter;
    }
}
