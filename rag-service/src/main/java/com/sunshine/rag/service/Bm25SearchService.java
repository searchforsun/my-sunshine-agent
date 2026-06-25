package com.sunshine.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.config.RagElasticsearchProperties;
import com.sunshine.rag.model.RetrievalCandidate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch BM25 全文检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Bm25SearchService {

    private final RagElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private WebClient webClient;

    @PostConstruct
    void init() {
        if (!properties.isEnabled()) {
            return;
        }
        webClient = WebClient.builder().baseUrl(properties.getUrl()).build();
    }

    public boolean isEnabled() {
        return properties.isEnabled() && webClient != null;
    }

    public Mono<List<RetrievalCandidate>> search(String query, int topK) {
        return search(query, topK, "default");
    }

    public Mono<List<RetrievalCandidate>> search(String query, int topK, String tenantId) {
        if (!isEnabled() || query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> searchBlocking(query, topK, tenantId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<RetrievalCandidate> searchBlocking(String query, int topK, String tenantId) throws Exception {
        Map<String, Object> body = buildSearchBody(query, topK, tenantId);
        String json = webClient.post()
                .uri("/{index}/_search", properties.getIndex())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(15));
        return parseHits(json);
    }

    /** 供单测校验 tenant 过滤 DSL */
    static Map<String, Object> buildSearchBody(String query, int topK, String tenantId) {
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", topK);
        body.put("query", Map.of(
                "bool", Map.of(
                        "must", List.of(
                                Map.of("multi_match", Map.of(
                                        "query", query,
                                        "fields", List.of("content^2", "doc_name"),
                                        "type", "best_fields")),
                                Map.of("term", Map.of("tenant_id", tid))))));
        return body;
    }

    List<RetrievalCandidate> parseHits(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode hits = objectMapper.readTree(json).path("hits").path("hits");
        List<RetrievalCandidate> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            String content = source.path("content").asText("");
            if (content.isBlank()) {
                continue;
            }
            String docName = source.path("doc_name").asText("未知文档");
            String chunkId = source.path("chunk_id").asText(null);
            float score = (float) hit.path("_score").asDouble(0.0);
            results.add(new RetrievalCandidate(
                    chunkId, docName, content, score, RetrievalCandidate.SOURCE_BM25));
        }
        log.info("[RAG-BM25] 检索完成: 返回={}", results.size());
        return results;
    }
}
