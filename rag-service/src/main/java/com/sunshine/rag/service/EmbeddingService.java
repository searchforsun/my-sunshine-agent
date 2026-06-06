package com.sunshine.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Embedding 向量化服务 — 调用通义千问 Embedding API
 */
@Slf4j
@Service
public class EmbeddingService {

    @Value("${embedding.api-key:}")
    private String apiKey;

    @Value("${embedding.base-url:https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding}")
    private String baseUrl;

    @Value("${embedding.model:text-embedding-v4}")
    private String model;

    private WebClient webClient;

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder()
                    .baseUrl(baseUrl)
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
                .doOnError(e -> log.error("[RAG] Embedding 调用失败", e));
    }
}
