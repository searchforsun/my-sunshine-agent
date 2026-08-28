package com.sunshine.orchestrator.client;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * rag-service 工具语义索引客户端（5.5 tool RAG）— 同步工具目录 + query 检索 Top-K 工具。
 */
@Slf4j
@Component
public class ToolRetrievalClient {

    private final WebClient webClient;
    private final AgentExecutionProperties executionProperties;

    public ToolRetrievalClient(WebClient.Builder builder, AgentExecutionProperties executionProperties) {
        this.webClient = builder.baseUrl("http://sunshine-rag").build();
        this.executionProperties = executionProperties;
        log.info("[ToolRetrievalClient] baseUrl=http://sunshine-rag");
    }

    /** 全量重建租户工具索引（幂等；工具目录变化后同步一次）。 */
    public Mono<Void> syncIndex(String tenantId, List<ToolIndexDoc> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default");
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolIndexDoc doc : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolId", doc.toolId());
            item.put("name", doc.name());
            item.put("description", doc.description());
            item.put("paramsSummary", doc.paramsSummary());
            items.add(item);
        }
        body.put("tools", items);
        return webClient.post()
                .uri("/api/tool-index/sync")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-tenant-id", tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .then()
                .doOnError(e -> log.warn("[ToolRetrievalClient] sync index failed tenant={}: {}",
                        tenantId, e.getMessage()));
    }

    /** query → Top-K 工具命中（分数降序）；minScore 透传 Nacos agent.execution.react.tool-inject.min-score。 */
    public Mono<List<ToolIndexHit>> search(String query, Integer topK, String tenantId) {
        float minScore = executionProperties.getReact().getToolInject().getMinScore();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("tenantId", tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default");
        if (topK != null && topK > 0) {
            body.put("topK", topK);
        }
        body.put("minScore", minScore);
        return webClient.post()
                .uri("/api/tool-index/search")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-tenant-id", tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> parseHits(response))
                .doOnError(e -> log.warn("[ToolRetrievalClient] search failed tenant={}: {}", tenantId, e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private static List<ToolIndexHit> parseHits(Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<ToolIndexHit> hits = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object toolId = map.get("toolId");
            if (toolId == null) {
                continue;
            }
            float score = map.get("score") instanceof Number n ? n.floatValue() : 0f;
            hits.add(new ToolIndexHit(String.valueOf(toolId).strip(), score));
        }
        return hits;
    }

    /** 工具目录条目（与 rag-service ToolIndexDoc 对应）。 */
    public record ToolIndexDoc(String toolId, String name, String description, String paramsSummary) {
    }

    public record ToolIndexHit(String toolId, float score) {
    }
}
