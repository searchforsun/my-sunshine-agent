package com.sunshine.llm.adapter;

import com.sunshine.llm.config.ProviderProperties;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
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
 * 通义千问 API 适配器（OpenAI 兼容协议）
 * Base URL 已含 /v1，因此 uri 路径无需再加 /v1 前缀
 */
@Slf4j
@Component
public class QwenAdapter implements LlmAdapter {

    private final ProviderProperties props;

    private WebClient client;

    public QwenAdapter(ProviderProperties props) {
        this.props = props;
    }

    @Override
    public boolean supports(String model) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
        if (config == null) {
            return false;
        }
        return config.getModels().contains(model) || model.startsWith("qwen-");
    }

    @Override
    public Mono<ChatCompletionResponse> chat(ChatCompletionRequest request) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
        String apiKey = config.getApiKey();

        return webClient()
                .post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toRequestBody(request, false))
                .retrieve()
                .bodyToMono(ChatCompletionResponse.class)
                .doOnNext(r -> log.info("[Qwen] tokens={}",
                        r.getUsage() != null ? r.getUsage().getTotalTokens() : "?"))
                .doOnError(e -> log.error("[Qwen] 调用失败", e));
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(ChatCompletionRequest request) {
        ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
        String apiKey = config.getApiKey();

        return webClient()
                .post()
                .uri("/chat/completions")
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
                .doOnError(e -> log.error("[Qwen] 流式调用失败", e));
    }

    // ========== private ==========

    private WebClient webClient() {
        if (client == null) {
            ProviderProperties.ProviderConfig config = props.getProviders().get("qwen");
            client = WebClient.builder()
                    .baseUrl(config.getBaseUrl())
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();
        }
        return client;
    }

    private Object toRequestBody(ChatCompletionRequest request, boolean stream) {
        List<Msg> messages = request.getMessages().stream()
                .map(m -> new Msg(m.getRole(), m.getContent()))
                .toList();
        return new QwenRequest(request.getModel(), messages,
                request.getTemperature(), request.getMaxTokens(), stream);
    }

    record QwenRequest(String model, List<Msg> messages, Double temperature,
                       Integer maxTokens, Boolean stream) {
    }

    record Msg(String role, String content) {
    }
}
