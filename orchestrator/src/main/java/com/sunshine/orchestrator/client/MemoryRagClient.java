package com.sunshine.orchestrator.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MTM 向量召回 — 调用 RAG Service {@code /api/rag/memory/*}。
 */
@Slf4j
@Component
public class MemoryRagClient {

    @Value("${rag.base-url:http://localhost:8400}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("[MemoryRagClient] baseUrl={}", baseUrl);
    }

    @SuppressWarnings("unchecked")
    public Mono<List<MemoryHit>> search(String userId, String tenantId, String query, int topK) {
        Map<String, Object> body = Map.of(
                "userId", userId,
                "tenantId", tenantId != null ? tenantId : "default",
                "query", query,
                "topK", topK);

        return webClient.post()
                .uri("/api/rag/memory/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(MemoryRagClient::parseSearchResults)
                .timeout(Duration.ofSeconds(3))
                .doOnError(e -> log.warn("[MemoryRagClient] search failed: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    static List<MemoryHit> parseSearchResults(Map<String, Object> response) {
        Object payload = response.get("data") instanceof Map<?, ?> dataMap
                ? dataMap
                : response;
        List<?> raw = payload instanceof Map<?, ?> map ? (List<?>) map.get("results") : null;
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<MemoryHit> hits = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> hitMap) {
                hits.add(parseHit(hitMap));
            }
        }
        return hits;
    }

    public Mono<Void> upsert(String userId, String tenantId, String convId, String summary) {
        Map<String, Object> body = Map.of(
                "userId", userId,
                "tenantId", tenantId != null ? tenantId : "default",
                "convId", convId,
                "summary", summary);

        return webClient.post()
                .uri("/api/rag/memory/upsert")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10))
                .doOnError(e -> log.warn("[MemoryRagClient] upsert failed conv={}: {}", convId, e.getMessage()))
                .onErrorComplete();
    }

    private static MemoryHit parseHit(Map<?, ?> map) {
        String convId = map.get("convId") != null ? map.get("convId").toString() : "";
        String summary = map.get("summary") != null ? map.get("summary").toString() : "";
        float score = 0f;
        Object scoreObj = map.get("score");
        if (scoreObj instanceof Number number) {
            score = number.floatValue();
        }
        return new MemoryHit(convId, summary, score);
    }

    public record MemoryHit(String convId, String summary, float score) {
    }
}
