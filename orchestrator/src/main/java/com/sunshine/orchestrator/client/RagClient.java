package com.sunshine.orchestrator.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
     * @param query 查询文本
     * @param topK  返回结果数量，默认 3
     * @return 文档片段内容列表
     */
    @SuppressWarnings("unchecked")
    public Mono<List<String>> search(String query, int topK) {
        Map<String, Object> body = Map.of("query", query, "topK", topK);

        return webClient.post()
                .uri("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .map(response -> {
                    List<?> rawList = (List<?>) response.get("results");
                    List<String> results = rawList != null
                            ? rawList.stream().map(Object::toString).toList()
                            : List.of();
                    log.info("[RagClient] 检索完成: query='{}', 命中 {} 条",
                            query.length() > 30 ? query.substring(0, 30) + "..." : query,
                            results.size());
                    return results;
                })
                .doOnError(e -> log.error("[RagClient] 检索失败: {}", e.getMessage()));
    }
}
