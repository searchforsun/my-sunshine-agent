package com.sunshine.llm.controller;

import com.sunshine.llm.cache.SemanticCacheService;
import com.sunshine.llm.filter.NormalizeFilter;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.router.ModelRouter;
import com.sunshine.llm.usage.QuotaCheckClient;
import com.sunshine.llm.usage.TokenUsageCollector;
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
    private final TokenUsageCollector usageCollector;
    private final QuotaCheckClient quotaCheckClient;

    @PostMapping("/chat/completions")
    public Object chatCompletions(
            @RequestBody ChatCompletionRequest request,
            @RequestHeader(value = "X-Fallback-Model", required = false) String fallbackHeader,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantIdHeader,
            @RequestHeader(value = "x-user-id", required = false) String userIdHeader) {
        if ((request.getFallbackModel() == null || request.getFallbackModel().isBlank())
                && fallbackHeader != null && !fallbackHeader.isBlank()) {
            request.setFallbackModel(fallbackHeader.strip());
        }
        QuotaCheckClient.Outcome quotaOutcome = quotaCheckClient.check(tenantIdHeader, request.getModel());
        if (!quotaOutcome.allowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(quotaRejectedBody(quotaOutcome.code()));
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
                        // 缓存命中不算 LLM 调用，仅真实路由的响应记录用量（5.2）
                        router.route(request)
                                .doOnNext(resp -> usageCollector.recordNonStream(request, resp))
                                .flatMap(resp -> cache.put(request, resp).thenReturn(resp))
                );
    }

    private Flux<ServerSentEvent<String>> streamCompletion(ChatCompletionRequest request) {
        request.setStream(true);
        // 流式用量：末尾 chunk 的 usage 优先，缺失时按 messages + 流式字符估算（5.2）
        TokenUsageCollector.StreamUsageAccumulator acc = usageCollector.newStreamAccumulator(request);
        return router.stream(request)
                .doOnNext(event -> acc.onChunk(event.data()))
                .doOnComplete(acc::complete);
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

    private Map<String, Object> quotaRejectedBody(String code) {
        String message = "model_not_allowed".equals(code)
                ? "模型不在租户配额白名单"
                : "租户月度用量配额已用尽";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", Map.of(
                "message", message,
                "type", "quota_error",
                "code", code));
        return body;
    }
}
