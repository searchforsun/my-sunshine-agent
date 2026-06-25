package com.sunshine.orchestrator.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * RAG Service HTTP 客户端
 * 调用 RAG Service 的 /api/rag/search 进行向量检索
 */
@Slf4j
@Component
public class RagClient {

    @Value("${rag.base-url:http://localhost:8400}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("[RagClient] 初始化完成: baseUrl={}", baseUrl);
    }

    /**
     * 检索知识库
     *
     * @param query 查询文本
     * @param topK  返回结果数量
     * @return 文档片段列表（含文档名称）
     */
    public Mono<List<RagHit>> search(String query, int topK) {
        return search(query, topK, null, "default");
    }

    public Mono<List<RagHit>> search(String query, int topK, String strategy) {
        return search(query, topK, strategy, "default");
    }

    @SuppressWarnings("unchecked")
    public Mono<List<RagHit>> search(String query, int topK, String strategy, String tenantId) {
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("topK", topK);
        body.put("tenantId", tid);
        if (strategy != null && !strategy.isBlank()) {
            body.put("strategy", strategy);
        }

        return webClient.post()
                .uri("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-tenant-id", tid)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .map(response -> {
                    List<?> rawList = (List<?>) response.get("results");
                    if (rawList == null || rawList.isEmpty()) {
                        return List.<RagHit>of();
                    }
                    List<RagHit> results = new ArrayList<>();
                    for (Object item : rawList) {
                        if (item instanceof Map<?, ?> map) {
                            results.add(parseHit(map));
                        } else {
                            results.add(new RagHit("未知文档", item.toString(), 0f));
                        }
                    }
                    log.info("[RagClient] 检索完成: query='{}', 命中 {} 条",
                            query.length() > 30 ? query.substring(0, 30) + "..." : query,
                            results.size());
                    return results;
                })
                .doOnError(e -> log.error("[RagClient] 检索失败: {}", e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private static RagHit parseHit(Map<?, ?> map) {
        String docName = map.get("docName") != null ? map.get("docName").toString() : "未知文档";
        String content = map.get("content") != null ? map.get("content").toString() : "";
        float score = 0f;
        Object scoreObj = map.get("score");
        if (scoreObj instanceof Number number) {
            score = number.floatValue();
        }
        return new RagHit(docName, content, score);
    }

    public record RagHit(String docName, String content, float score) {
    }
}
