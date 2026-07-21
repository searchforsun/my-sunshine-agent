package com.sunshine.rag.service;

import com.sunshine.rag.config.RagElasticsearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        indexChunk(chunkId, docName, content, chunkIndex, tenantId, "default", docName,
                com.sunshine.rag.util.DocumentVersionTime.now(), "active", "markdown");
    }

    public void indexChunk(
            String chunkId,
            String docName,
            String content,
            int chunkIndex,
            String tenantId,
            String kbId,
            String docId,
            String version,
            String status,
            String sourceType) {
        indexChunk(chunkId, docName, content, chunkIndex, tenantId, kbId, docId, version, status, sourceType,
                null, null, null);
    }

    public void indexChunk(
            String chunkId,
            String docName,
            String content,
            int chunkIndex,
            String tenantId,
            String kbId,
            String docId,
            String version,
            String status,
            String sourceType,
            String strategy,
            String chunkLevel,
            String parentChunkId) {
        if (!isEnabled()) {
            return;
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("chunk_id", chunkId);
        doc.put("doc_name", docName);
        doc.put("content", content);
        doc.put("chunk_index", chunkIndex);
        doc.put("tenant_id", tenantId != null ? tenantId : "default");
        doc.put("kb_id", kbId != null ? kbId : "default");
        doc.put("doc_id", docId != null ? docId : docName);
        doc.put("version", com.sunshine.rag.util.DocumentVersionTime.toMilvusCode(version));
        doc.put("status", status != null ? status : "active");
        doc.put("source_type", sourceType != null ? sourceType : "markdown");
        doc.put("strategy", strategy != null ? strategy : "");
        doc.put("chunk_level", chunkLevel != null ? chunkLevel : "");
        doc.put("parent_chunk_id", parentChunkId != null ? parentChunkId : "");
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

    public void deleteByDocVersion(String tenantId, String kbId, String docId, String version) {
        if (!isEnabled()) {
            return;
        }
        String tid = tenantId != null ? tenantId : "default";
        String kid = kbId != null ? kbId : "default";
        Map<String, Object> body = Map.of(
                "query", Map.of(
                        "bool", Map.of(
                                "must", List.of(
                                        Map.of("term", Map.of("tenant_id", tid)),
                                        Map.of("term", Map.of("kb_id", kid)),
                                        Map.of("term", Map.of("doc_id", docId)),
                                        Map.of("term", Map.of("version", com.sunshine.rag.util.DocumentVersionTime.toMilvusCode(version)))))));
        try {
            webClient.post()
                    .uri("/{index}/_delete_by_query", properties.getIndex())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(15));
        } catch (Exception e) {
            log.warn("[RAG-ES] delete_by_query failed doc={} v={}: {}", docId, version, e.getMessage());
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

    /** 按 chunk_id 取正文（parent_child 父块回填） */
    @SuppressWarnings("unchecked")
    public String fetchContentByChunkId(String chunkId) {
        if (!isEnabled() || chunkId == null || chunkId.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> response = webClient.get()
                    .uri("/{index}/_doc/{id}", properties.getIndex(), chunkId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(10));
            if (response == null) {
                return null;
            }
            Object sourceObj = response.get("_source");
            if (!(sourceObj instanceof Map<?, ?> source)) {
                return null;
            }
            Object content = source.get("content");
            return content != null ? content.toString() : null;
        } catch (Exception e) {
            log.debug("[RAG-ES] fetch chunkId={}: {}", chunkId, e.getMessage());
            return null;
        }
    }

    /** 按文档版本查询 chunk（管理预览） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> queryChunksByDocVersion(
            String tenantId, String kbId, String docId, String version) {
        if (!isEnabled()) {
            return List.of();
        }
        String tid = tenantId != null ? tenantId : "default";
        String kid = kbId != null ? kbId : "default";
        Map<String, Object> body = Map.of(
                "query", Map.of(
                        "bool", Map.of(
                                "must", List.of(
                                        Map.of("term", Map.of("tenant_id", tid)),
                                        Map.of("term", Map.of("kb_id", kid)),
                                        Map.of("term", Map.of("doc_id", docId)),
                                        Map.of("term", Map.of("version", com.sunshine.rag.util.DocumentVersionTime.toMilvusCode(version)))))),
                "sort", List.of(Map.of("chunk_index", Map.of("order", "asc"))),
                "size", 500);
        try {
            Map<String, Object> response = webClient.post()
                    .uri("/{index}/_search", properties.getIndex())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(15));
            if (response == null) {
                return List.of();
            }
            Object hitsObj = response.get("hits");
            if (!(hitsObj instanceof Map<?, ?> hits)) {
                return List.of();
            }
            Object hitListObj = hits.get("hits");
            if (!(hitListObj instanceof List<?> hitList)) {
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : hitList) {
                if (!(item instanceof Map<?, ?> hit)) {
                    continue;
                }
                Object sourceObj = hit.get("_source");
                if (sourceObj instanceof Map<?, ?> source) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    source.forEach((k, v) -> row.put(String.valueOf(k), v));
                    result.add(row);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[RAG-ES] query chunks failed doc={} v={}: {}", docId, version, e.getMessage());
            return List.of();
        }
    }

    private void ensureIndex() {
        if (!isEnabled()) {
            return;
        }
        Map<String, Object> propertiesMap = new LinkedHashMap<>();
        propertiesMap.put("chunk_id", Map.of("type", "keyword"));
        propertiesMap.put("doc_name", Map.of(
                "type", "text",
                "fields", Map.of("keyword", Map.of("type", "keyword"))));
        propertiesMap.put("content", Map.of("type", "text"));
        propertiesMap.put("chunk_index", Map.of("type", "integer"));
        propertiesMap.put("tenant_id", Map.of("type", "keyword"));
        propertiesMap.put("kb_id", Map.of("type", "keyword"));
        propertiesMap.put("doc_id", Map.of("type", "keyword"));
        propertiesMap.put("version", Map.of("type", "long"));
        propertiesMap.put("status", Map.of("type", "keyword"));
        propertiesMap.put("source_type", Map.of("type", "keyword"));
        propertiesMap.put("strategy", Map.of("type", "keyword"));
        propertiesMap.put("chunk_level", Map.of("type", "keyword"));
        propertiesMap.put("parent_chunk_id", Map.of("type", "keyword"));
        propertiesMap.put("section_path", Map.of("type", "keyword"));
        Map<String, Object> body = Map.of("mappings", Map.of("properties", propertiesMap));
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
