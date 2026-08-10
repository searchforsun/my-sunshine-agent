package com.sunshine.llm.controller;

import com.sunshine.llm.cache.SemanticCacheService;
import com.sunshine.llm.filter.NormalizeFilter;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.router.ModelRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

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
    public Object chatCompletions(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "X-Fallback-Model", required = false) String fallbackHeader) {
        if ((request.getFallbackModel() == null || request.getFallbackModel().isBlank())
                && fallbackHeader != null && !fallbackHeader.isBlank()) {
            request.setFallbackModel(fallbackHeader.strip());
        }
        if (Boolean.TRUE.equals(request.getStream())) {
            log.info("[LLM-GW] 流式(OpenAI 兼容): model={}", request.getModel());
            return toSseEmitter(streamCompletion(request));
        }
        return chatCompletion(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        String code = e.getMessage();
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (NormalizeFilter.MODEL_NOT_MULTIMODAL.equals(code)
                || NormalizeFilter.MODEL_NOT_TOOL_CALL.equals(code)
                || (code != null && code.startsWith("不支持的模型"))) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", Map.of(
                    "message", code,
                    "type", "invalid_request_error",
                    "code", code));
            return ResponseEntity.status(status).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", Map.of(
                "message", code != null ? code : "bad_request",
                "type", "invalid_request_error",
                "code", "bad_request"));
        return ResponseEntity.status(status).body(body);
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
