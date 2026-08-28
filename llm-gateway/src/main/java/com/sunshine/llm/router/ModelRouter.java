package com.sunshine.llm.router;

import com.sunshine.llm.adapter.LlmAdapter;
import com.sunshine.llm.filter.NormalizeFilter;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.registry.ModelDefinitionView;
import com.sunshine.llm.registry.ModelRegistryCache;
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
 * 模型路由器 — 注册表选 Adapter；失败或熔断时按 fallback_model / 场景绑定降级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouter {

    private final List<LlmAdapter> adapters;
    private final ModelRegistryCache registryCache;
    private final NormalizeFilter normalizeFilter;
    private final AdapterCircuitBreaker circuitBreaker;
    private final LlmIoTracer ioTracer;

    @PostConstruct
    public void init() {
        log.info("[LLM-GW] 已注册 {} 个适配器", adapters.size());
        adapters.forEach(a -> log.info("[LLM-GW]   - {}", a.getClass().getSimpleName()));
    }

    public Mono<ChatCompletionResponse> route(ChatCompletionRequest request) {
        return invokeChat(resolveEffectiveModel(request), request, new HashSet<>());
    }

    public Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        return invokeStream(resolveEffectiveModel(request), request, new HashSet<>());
    }

    /**
     * 模型解析（phase5 5.3）：显式指定 model 直路由；
     * model=auto 或缺省时按请求 call_site 查路由策略表选首个可用模型。
     * 生效模型回写请求体：用量计量（5.2）按实际生效模型落库，语义缓存 key 亦以生效模型隔离。
     */
    private String resolveEffectiveModel(ChatCompletionRequest request) {
        String model = request.getModel();
        if (model != null && !model.isBlank() && !"auto".equalsIgnoreCase(model.strip())) {
            return model.strip();
        }
        String effective = registryCache.routeModelFor(request.getCallSite())
                .orElseThrow(() -> new IllegalArgumentException(
                        "model=auto 但调用点无可用路由策略: callSite=" + request.getCallSite()
                                + "（请在模型注册表配置 model_route_policy 或显式指定 model）"));
        request.setModel(effective);
        return effective;
    }

    private Mono<ChatCompletionResponse> invokeChat(
            String model, ChatCompletionRequest request, Set<String> tried) {
        if (!circuitBreaker.allowRequest(model)) {
            return tryFallbackChat(model, request, tried,
                    new IllegalStateException("模型熔断中: " + model));
        }
        ChatCompletionRequest routed = withModel(request, model);
        try {
            validateAgainstRegistry(routed);
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        LlmAdapter adapter = findAdapter(model);
        log.info("[LLM-GW] {} → {}", model, adapter.getClass().getSimpleName());
        return adapter.chat(routed)
                .doOnSuccess(r -> circuitBreaker.recordSuccess(model))
                .onErrorResume(e -> {
                    if (isCapabilityError(e)) {
                        return Mono.error(e);
                    }
                    circuitBreaker.recordFailure(model);
                    return tryFallbackChat(model, request, tried, e);
                });
    }

    private Mono<ChatCompletionResponse> tryFallbackChat(
            String model, ChatCompletionRequest request, Set<String> tried, Throwable cause) {
        String fallback = resolveFallback(request, model);
        if (fallback == null || tried.contains(fallback) || fallback.equals(model)) {
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
        ChatCompletionRequest routed = withModel(request, model);
        try {
            validateAgainstRegistry(routed);
        } catch (IllegalArgumentException e) {
            return Flux.error(e);
        }
        LlmAdapter adapter = findAdapter(model);
        boolean reasoning = registryCache.findDefinition(model)
                .map(d -> d.getCapabilities() != null && d.getCapabilities().isReasoning())
                .orElse(false);
        log.info("[LLM-GW] stream {} → {}", model, adapter.getClass().getSimpleName());
        ioTracer.logRequest(routed);
        // 与 Adapter 内归一化互补：兜底未提升的 reasoning_details / <think>
        final NormalizeFilter.ReasoningStreamState streamState =
                new NormalizeFilter.ReasoningStreamState();
        return ioTracer.traceStream(model, adapter.stream(routed)
                .map(event -> mapStreamEvent(event, reasoning, streamState))
                .doOnComplete(() -> circuitBreaker.recordSuccess(model))
                .onErrorResume(e -> {
                    if (isCapabilityError(e)) {
                        return Flux.error(e);
                    }
                    circuitBreaker.recordFailure(model);
                    return tryFallbackStream(model, request, tried, e);
                }));
    }

    private Flux<ServerSentEvent<String>> tryFallbackStream(
            String model, ChatCompletionRequest request, Set<String> tried, Throwable cause) {
        String fallback = resolveFallback(request, model);
        if (fallback == null || tried.contains(fallback) || fallback.equals(model)) {
            return Flux.error(cause);
        }
        tried.add(model);
        log.warn("[LLM-GW] stream {} 失败，降级到 {}: {}", model, fallback, cause.getMessage());
        return invokeStream(fallback, request, tried);
    }

    private void validateAgainstRegistry(ChatCompletionRequest request) {
        ModelDefinitionView definition = registryCache.findDefinition(request.getModel())
                .filter(ModelDefinitionView::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("不支持的模型: " + request.getModel()));
        normalizeFilter.validateRequest(request, definition);
    }

    private String resolveFallback(ChatCompletionRequest request, String model) {
        if (request.getFallbackModel() != null && !request.getFallbackModel().isBlank()) {
            return request.getFallbackModel().strip();
        }
        return registryCache.fallbackForModel(model).orElse(null);
    }

    private ServerSentEvent<String> mapStreamEvent(
            ServerSentEvent<String> event,
            boolean reasoning,
            NormalizeFilter.ReasoningStreamState streamState) {
        if (event == null || event.data() == null) {
            return event;
        }
        String normalized = normalizeFilter.normalizeStreamData(event.data(), reasoning, streamState);
        if (normalized.equals(event.data())) {
            return event;
        }
        return ServerSentEvent.<String>builder()
                .id(event.id())
                .event(event.event())
                .comment(event.comment())
                .retry(event.retry())
                .data(normalized)
                .build();
    }

    private LlmAdapter findAdapter(String model) {
        return adapters.stream()
                .filter(a -> a.supports(model))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的模型: " + model));
    }

    private static boolean isCapabilityError(Throwable e) {
        if (!(e instanceof IllegalArgumentException)) {
            return false;
        }
        String msg = e.getMessage();
        return NormalizeFilter.MODEL_NOT_MULTIMODAL.equals(msg)
                || NormalizeFilter.MODEL_NOT_TOOL_CALL.equals(msg);
    }

    private static ChatCompletionRequest withModel(ChatCompletionRequest source, String model) {
        return source.copyWithModel(model);
    }
}
