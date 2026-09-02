package com.sunshine.rag.service;

import com.sunshine.rag.config.RagWebClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Embedding 向量化服务 — 调用通义千问 Embedding API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    /** 单次调用响应超时；连接异常（如对端 reset）原样重试一次，避免瞬时网络抖动直接打挂整条检索链路 */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final RagWebClientFactory webClientFactory;

    @Value("${embedding.api-key:}")
    private String apiKey;

    @Value("${embedding.base-url:https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding}")
    private String baseUrl;

    @Value("${embedding.model:text-embedding-v4}")
    private String model;

    private WebClient webClient;

    private WebClient client() {
        if (webClient == null) {
            webClient = webClientFactory.create(baseUrl, RESPONSE_TIMEOUT)
                    .mutate()
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();
        }
        return webClient;
    }

    @SuppressWarnings("unchecked")
    public Mono<List<Float>> embed(String text) {
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("texts", List.of(text)),
                "parameters", Map.of("text_type", "document")
        );

        return client().post()
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    Map<String, Object> output = (Map<String, Object>) response.get("output");
                    List<Map<String, Object>> embeddings =
                            (List<Map<String, Object>>) output.get("embeddings");
                    Map<String, Object> first = embeddings.get(0);
                    List<Number> vector = (List<Number>) first.get("embedding");
                    return vector.stream()
                            .map(Number::floatValue)
                            .toList();
                })
                .retryWhen(Retry.backoff(1, Duration.ofMillis(300))
                        .maxBackoff(Duration.ofSeconds(2))
                        .filter(EmbeddingService::isConnectionError))
                .doOnError(e -> log.error("[RAG] Embedding 调用失败", e));
    }

    /** 仅连接层 IO 异常（Connection reset / 超时等）可重试；业务/参数类 4xx 不重试 */
    private static boolean isConnectionError(Throwable e) {
        if (e instanceof WebClientRequestException) {
            Throwable cause = e.getCause();
            return cause instanceof IOException;
        }
        return false;
    }
}
