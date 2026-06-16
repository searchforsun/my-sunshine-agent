package com.sunshine.llm.adapter;

import com.sunshine.llm.config.LlmWebClientFactory;
import com.sunshine.llm.config.ProviderProperties;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * DeepSeek V4 API 适配器（OpenAI 兼容协议）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekAdapter implements LlmAdapter {

    private final ProviderProperties props;
    private final LlmWebClientFactory webClientFactory;

    private WebClient client;

    @Override
    public boolean supports(String model) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("deepseek");
        if (config == null) {
            return false;
        }
        return config.getModels().contains(model) || model.startsWith("deepseek-");
    }

    @Override
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("deepseek");
        String apiKey = config.getApiKey();

        return webClient()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toRequestBody(request, false))
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .doOnNext(r -> log.info("[DeepSeek] tokens={}",
                        r.getUsage() != null ? r.getUsage().getTotalTokens() : "?"))
                .doOnError(e -> log.error("[DeepSeek] 调用失败", e));
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("deepseek");
        String apiKey = config.getApiKey();

        return webClient()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(toRequestBody(request, true))
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString().substring(0, 8))
                        .data(chunk)
                        .build())
                .doOnError(e -> log.error("[DeepSeek] 流式调用失败", e));
    }

    // ========== private ==========

    private WebClient webClient() {
        if (client == null) {
            ProviderProperties.ProviderConfig config = props.getProviders().get("deepseek");
            client = webClientFactory.create(config.getBaseUrl());
        }
        return client;
    }

    private Object toRequestBody(ChatCompletionRequest request, boolean stream) {
        List<Msg> messages = request.getMessages().stream()
                .map(m -> new Msg(m.getRole(), m.getContent()))
                .toList();
        return new DeepSeekRequest(request.getModel(), messages,
                request.getTemperature(), request.getMaxTokens(), stream);
    }

    record DeepSeekRequest(String model, List<Msg> messages, Double temperature,
                           Integer maxTokens, Boolean stream) {
    }

    record Msg(String role, String content) {
    }
}
