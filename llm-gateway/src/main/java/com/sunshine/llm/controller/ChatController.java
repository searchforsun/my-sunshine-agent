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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 大模型网关控制器 — OpenAI 兼容 /v1/chat/completions
 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatController {

    private final ModelRouter router;
    private final SemanticCacheService cache;

    @PostMapping("/chat/completions")
    public Mono<ChatCompletionResponse> chat(@RequestBody ChatCompletionRequest request) {
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

    @PostMapping(value = "/chat/completions/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatCompletionRequest request) {
        log.info("[LLM-GW] 流式: model={}", request.getModel());
        return router.stream(request);
    }
}
