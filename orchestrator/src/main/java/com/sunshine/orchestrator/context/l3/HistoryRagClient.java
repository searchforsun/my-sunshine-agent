package com.sunshine.orchestrator.context.l3;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L3 对话历史 RAG — 调用 rag-service {@code /api/rag/chat-history/*}（collection sunshine_chat_history）。
 */
@Slf4j
@Component
public class HistoryRagClient {

    private final WebClient webClient;

    public HistoryRagClient(WebClient.Builder builder) {
        this.webClient = builder
                .baseUrl("http://sunshine-rag")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("[HistoryRagClient] baseUrl=http://sunshine-rag");
    }

    public Mono<List<HistoryHit>> search(String userId, String tenantId, String query, int topK) {
        Map<String, Object> body = Map.of(
                "userId", userId,
                "tenantId", tenantId != null ? tenantId : "default",
                "query", query != null ? query : "",
                "topK", topK);

        return webClient.post()
                .uri("/api/rag/chat-history/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(HistoryRagClient::parseSearchResults)
                .timeout(Duration.ofSeconds(3))
                .doOnError(e -> log.warn("[HistoryRagClient] search failed: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }

    public Mono<Void> upsert(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            String content,
            long createdAtMs) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("tenantId", tenantId != null ? tenantId : "default");
        body.put("convId", convId != null ? convId : "");
        body.put("msgId", msgId);
        body.put("content", content != null ? content : "");
        body.put("createdAt", createdAtMs);

        return webClient.post()
                .uri("/api/rag/chat-history/upsert")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(30))
                .doOnError(e -> log.warn("[HistoryRagClient] upsert failed msg={}: {}", msgId, e.getMessage()))
                .onErrorComplete();
    }

    public Mono<Void> delete(String userId, String tenantId, String msgId) {
        Map<String, Object> body = Map.of(
                "userId", userId,
                "tenantId", tenantId != null ? tenantId : "default",
                "msgId", msgId);

        return webClient.post()
                .uri("/api/rag/chat-history/delete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10))
                .doOnError(e -> log.warn("[HistoryRagClient] delete failed msg={}: {}", msgId, e.getMessage()))
                .onErrorComplete();
    }

    public Mono<List<HistoryChunk>> listByConv(String userId, String tenantId, String convId, int limit) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("tenantId", tenantId != null ? tenantId : "default");
        body.put("convId", convId != null ? convId : "");
        body.put("limit", Math.max(1, limit));

        return webClient.post()
                .uri("/api/rag/chat-history/list")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(HistoryRagClient::parseListResults)
                .timeout(Duration.ofSeconds(10))
                .doOnError(e -> log.warn("[HistoryRagClient] list failed conv={}: {}", convId, e.getMessage()))
                .onErrorReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    static List<HistoryHit> parseSearchResults(Map<String, Object> response) {
        Object payload = response.get("data") instanceof Map<?, ?> dataMap ? dataMap : response;
        List<?> raw = payload instanceof Map<?, ?> map ? (List<?>) map.get("results") : null;
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<HistoryHit> hits = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> hitMap) {
                hits.add(parseHit(hitMap));
            }
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    static List<HistoryChunk> parseListResults(Map<String, Object> response) {
        Object payload = response.get("data") instanceof Map<?, ?> dataMap ? dataMap : response;
        List<?> raw = payload instanceof Map<?, ?> map ? (List<?>) map.get("results") : null;
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<HistoryChunk> chunks = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                chunks.add(parseChunk(map));
            }
        }
        return chunks;
    }

    private static HistoryHit parseHit(Map<?, ?> map) {
        String convId = map.get("convId") != null ? map.get("convId").toString() : "";
        String msgId = map.get("msgId") != null ? map.get("msgId").toString() : "";
        String content = map.get("content") != null ? map.get("content").toString() : "";
        float score = 0f;
        Object scoreObj = map.get("score");
        if (scoreObj instanceof Number number) {
            score = number.floatValue();
        }
        long createdAt = 0L;
        Object createdObj = map.get("createdAt");
        if (createdObj instanceof Number number) {
            createdAt = number.longValue();
        }
        return new HistoryHit(convId, msgId, content, score, createdAt);
    }

    private static HistoryChunk parseChunk(Map<?, ?> map) {
        String convId = map.get("convId") != null ? map.get("convId").toString() : "";
        String msgId = map.get("msgId") != null ? map.get("msgId").toString() : "";
        String content = map.get("content") != null ? map.get("content").toString() : "";
        int chunkIndex = 0;
        Object idxObj = map.get("chunkIndex");
        if (idxObj instanceof Number number) {
            chunkIndex = number.intValue();
        }
        long createdAt = 0L;
        Object createdObj = map.get("createdAt");
        if (createdObj instanceof Number number) {
            createdAt = number.longValue();
        }
        return new HistoryChunk(convId, msgId, chunkIndex, content, createdAt);
    }

    public record HistoryHit(String convId, String msgId, String content, float score, long createdAtMs) {
    }

    public record HistoryChunk(String convId, String msgId, int chunkIndex, String content, long createdAtMs) {
    }
}
