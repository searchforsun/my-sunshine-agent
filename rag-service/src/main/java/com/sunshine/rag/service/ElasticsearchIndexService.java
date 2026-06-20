package com.sunshine.rag.service;

import com.sunshine.rag.config.RagElasticsearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 知识库 chunk 双写 Elasticsearch（BM25 检索索引）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElasticsearchIndexService {

    private final RagElasticsearchProperties properties;
    private WebClient webClient;

    @PostConstruct
    void init() {
        if (!properties.isEnabled()) {
            return;
        }
        webClient = WebClient.builder().baseUrl(properties.getUrl()).build();
        ensureIndex();
        log.info("[RAG-ES] enabled url={} index={}", properties.getUrl(), properties.getIndex());
    }

    public boolean isEnabled() {
        return properties.isEnabled() && webClient != null;
    }

    public void indexChunk(String chunkId, String docName, String content, int chunkIndex, String tenantId) {
        if (!isEnabled()) {
            return;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("chunk_id", chunkId);
        doc.put("doc_name", docName);
        doc.put("content", content);
        doc.put("chunk_index", chunkIndex);
        doc.put("tenant_id", tenantId != null ? tenantId : "default");
        try {
            webClient.put()
                    .uri("/{index}/_doc/{id}", properties.getIndex(), chunkId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(doc)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("[RAG-ES] index failed chunkId={}: {}", chunkId, e.getMessage());
        }
    }

    public void rebuildIndex() {
        if (!isEnabled()) {
            return;
        }
        try {
            webClient.delete()
                    .uri("/{index}", properties.getIndex())
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(15));
        } catch (Exception e) {
            log.debug("[RAG-ES] delete index (may not exist): {}", e.getMessage());
        }
        ensureIndex();
        log.warn("[RAG-ES] index rebuilt: {}", properties.getIndex());
    }

    private void ensureIndex() {
        if (!isEnabled()) {
            return;
        }
        Map<String, Object> body = Map.of(
                "mappings", Map.of(
                        "properties", Map.of(
                                "chunk_id", Map.of("type", "keyword"),
                                "doc_name", Map.of(
                                        "type", "text",
                                        "fields", Map.of("keyword", Map.of("type", "keyword"))),
                                "content", Map.of("type", "text"),
                                "chunk_index", Map.of("type", "integer"),
                                "tenant_id", Map.of("type", "keyword"),
                                "section_path", Map.of("type", "keyword")
                        )));
        try {
            webClient.put()
                    .uri("/{index}", properties.getIndex())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(15));
        } catch (Exception e) {
            log.warn("[RAG-ES] ensure index failed: {}", e.getMessage());
        }
    }
}
