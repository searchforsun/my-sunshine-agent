package com.sunshine.llm.router;

import com.sunshine.llm.adapter.LlmAdapter;
import com.sunshine.llm.config.ModelFallbackProperties;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.trace.LlmIoTracer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型路由器 — 根据 model 选择厂商；失败或熔断时自动降级到备用模型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final List<LlmAdapter> adapters;
    private final ModelFallbackProperties fallbackProperties;
    private final AdapterCircuitBreaker circuitBreaker;
    private final LlmIoTracer ioTracer;

    @PostConstruct
    public void init() {
        log.info("[LLM-GW] 已注册 {} 个适配器", adapters.size());
        adapters.forEach(a -> log.info("[LLM-GW]   - {}", a.getClass().getSimpleName()));
        if (fallbackProperties.getRoutes() != null) {
            fallbackProperties.getRoutes().forEach((primary, fb) ->
                    log.info("[LLM-GW] 降级链 {} → {}", primary, fb));
        }
    }

    public Mono<ChatCompletionResponse> route(ChatCompletionRequest request) {
        return invokeChat(request.getModel(), request, new HashSet<>());
    }

    public Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        return invokeStream(request.getModel(), request, new HashSet<>());
    }

    private Mono<ChatCompletionResponse> invokeChat(
            String model, ChatCompletionRequest request, Set<String> tried) {
        if (!circuitBreaker.allowRequest(model)) {
            return tryFallbackChat(model, request, tried,
                    new IllegalStateException("模型熔断中: " + model));
        }
        LlmAdapter adapter = findAdapter(model);
        ChatCompletionRequest routed = withModel(request, model);
        log.info("[LLM-GW] {} → {}", model, adapter.getClass().getSimpleName());
        return adapter.chat(routed)
                .doOnSuccess(r -> circuitBreaker.recordSuccess(model))
                .onErrorResume(e -> {
                    circuitBreaker.recordFailure(model);
                    return tryFallbackChat(model, request, tried, e);
                });
    }

    private Mono<ChatCompletionResponse> tryFallbackChat(
            String model, ChatCompletionRequest request, Set<String> tried, Throwable cause) {
        String fallback = fallbackProperties.fallbackFor(model);
        if (fallback == null || tried.contains(fallback)) {
            return Mono.error(cause);
        }
        tried.add(model);
        log.warn("[LLM-GW] {} 调用失败，降级到 {}: {}", model, fallback, cause.getMessage());
        return invokeChat(fallback, request, tried);
    }

    private Flux<ServerSentEvent<String>> invokeStream(
            String model, ChatCompletionRequest request, Set<String> tried) {
        if (!circuitBreaker.allowRequest(model)) {
            return tryFallbackStream(model, request, tried,
                    new IllegalStateException("模型熔断中: " + model));
        }
        LlmAdapter adapter = findAdapter(model);
        ChatCompletionRequest routed = withModel(request, model);
        log.info("[LLM-GW] stream {} → {}", model, adapter.getClass().getSimpleName());
        ioTracer.logRequest(routed);
        return ioTracer.traceStream(model, adapter.stream(routed)
                .doOnComplete(() -> circuitBreaker.recordSuccess(model))
                .onErrorResume(e -> {
                    circuitBreaker.recordFailure(model);
                    return tryFallbackStream(model, request, tried, e);
                }));
    }

    private Flux<ServerSentEvent<String>> tryFallbackStream(
            String model, ChatCompletionRequest request, Set<String> tried, Throwable cause) {
        String fallback = fallbackProperties.fallbackFor(model);
        if (fallback == null || tried.contains(fallback)) {
            return Flux.error(cause);
        }
        tried.add(model);
        log.warn("[LLM-GW] stream {} 失败，降级到 {}: {}", model, fallback, cause.getMessage());
        return invokeStream(fallback, request, tried);
    }

    private LlmAdapter findAdapter(String model) {
        return adapters.stream()
                .filter(a -> a.supports(model))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的模型: " + model));
    }

    private static ChatCompletionRequest withModel(ChatCompletionRequest source, String model) {
        ChatCompletionRequest copy = new ChatCompletionRequest();
        copy.setModel(model);
        copy.setMessages(source.getMessages());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setStream(source.getStream());
        return copy;
    }
}
