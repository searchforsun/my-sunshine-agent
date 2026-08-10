package com.sunshine.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.config.LlmWebClientFactory;
import com.sunshine.llm.filter.NormalizeFilter;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import com.sunshine.llm.registry.ModelCapabilities;
import com.sunshine.llm.registry.ModelDefinitionView;
import com.sunshine.llm.registry.ModelProviderView;
import com.sunshine.llm.registry.ModelRegistryCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * 统一 OpenAI 兼容适配器：路由键仅认注册表 enabled 模型，禁止前缀猜测。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleAdapter implements LlmAdapter {

    private final ModelRegistryCache registryCache;
    private final LlmWebClientFactory webClientFactory;
    private final OpenAiRequestBodyFactory requestBodyFactory;
    private final NormalizeFilter normalizeFilter;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String model) {
        return registryCache.findDefinition(model)
                .filter(ModelDefinitionView::isEnabled)
                .isPresent();
    }

    @Override
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request) {
        ResolvedTarget target = resolve(request.getModel());
        Map<String, Object> body = requestBodyFactory.build(
                request, false, capabilitiesOf(target.definition()));
        return clientFor(target.provider())
                .post()
                .uri(chatCompletionsPath(target.provider().getPathPrefix()))
                .header("Authorization", "Bearer " + target.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(raw -> normalizeFilter.normalizeResponseBody(raw, reasoningEnabled(target.definition())))
                .map(this::toResponse)
                .doOnNext(r -> log.info("[OpenAI-Compat] model={} tokens={}",
                        request.getModel(),
                        r.getUsage() != null ? r.getUsage().getTotalTokens() : "?"))
                .doOnError(e -> log.error("[OpenAI-Compat] chat failed model={}: {}",
                        request.getModel(), errorDetail(e), e));
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        ResolvedTarget target = resolve(request.getModel());
        boolean reasoning = reasoningEnabled(target.definition());
        Map<String, Object> body = requestBodyFactory.build(
                request, true, capabilitiesOf(target.definition()));
        // 每条流独立累计：MiniMax reasoning_details 常为全文，需切成增量
        final NormalizeFilter.ReasoningStreamState state =
                new NormalizeFilter.ReasoningStreamState();
        return clientFor(target.provider())
                .post()
                .uri(chatCompletionsPath(target.provider().getPathPrefix()))
                .header("Authorization", "Bearer " + target.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> normalizeFilter.normalizeStreamData(chunk, reasoning, state))
                .map(chunk -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString().substring(0, 8))
                        .data(chunk)
                        .build())
                .doOnError(e -> log.error("[OpenAI-Compat] stream failed model={}: {}",
                        request.getModel(), errorDetail(e), e));
    }

    static String chatCompletionsPath(String pathPrefix) {
        String prefix = pathPrefix == null ? "" : pathPrefix.strip();
        if (prefix.isEmpty()) {
            return "/chat/completions";
        }
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/chat/completions";
    }

    private ResolvedTarget resolve(String model) {
        ModelDefinitionView definition = registryCache.findDefinition(model)
                .filter(ModelDefinitionView::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("不支持的模型: " + model));
        ModelProviderView provider = registryCache.findProvider(definition.getProviderKey())
                .filter(ModelProviderView::isEnabled)
                .orElseThrow(() -> new IllegalStateException(
                        "provider not found or disabled: " + definition.getProviderKey()));
        String apiKey = registryCache.decryptApiKey(provider);
        return new ResolvedTarget(definition, provider, apiKey);
    }

    private WebClient clientFor(ModelProviderView provider) {
        return webClientFactory.create(provider.getBaseUrl());
    }

    private ChatCompletionResponse toResponse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.treeToValue(node, ChatCompletionResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to parse upstream chat response", e);
        }
    }

    private static ModelCapabilities capabilitiesOf(ModelDefinitionView definition) {
        return definition.getCapabilities() != null
                ? definition.getCapabilities()
                : ModelCapabilities.defaults();
    }

    private static boolean reasoningEnabled(ModelDefinitionView definition) {
        return capabilitiesOf(definition).isReasoning();
    }

    private static String errorDetail(Throwable e) {
        if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
            String body = wce.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                return body;
            }
        }
        return e.getMessage();
    }

    private record ResolvedTarget(
            ModelDefinitionView definition, ModelProviderView provider, String apiKey) {}
}
