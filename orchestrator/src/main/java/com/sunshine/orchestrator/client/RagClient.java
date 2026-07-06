package com.sunshine.orchestrator.client;

import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
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
 * RAG Service HTTP 客户端 — 调用 rag-service pipeline（ADR-002）。
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

    public Mono<String> fetchDefaultKbId(String tenantId) {
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        return webClient.get()
                .uri("/api/rag/admin/kbs/default")
                .header("x-tenant-id", tid)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> {
                    Object data = response.get("data");
                    if (data instanceof Map<?, ?> map && map.get("kbId") != null) {
                        return map.get("kbId").toString();
                    }
                    return "default";
                })
                .doOnError(e -> log.warn("[RagClient] 默认 kb 解析失败: {}", e.getMessage()))
                .onErrorReturn("default");
    }

    /** 干净检索 API：topK 为 null 时由 rag-service Nacos default-top-k 决定 */
    public Mono<RagSearchResult> searchKnowledge(
            String query, Integer topK, String tenantId, String kbId, String strategy, boolean includeTrace) {
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        String kid = kbId != null && !kbId.isBlank() ? kbId.strip() : "default";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (topK != null) {
            body.put("topK", topK);
        }
        body.put("tenantId", tid);
        body.put("kbId", kid);
        if (strategy != null && !strategy.isBlank()) {
            body.put("strategy", strategy);
        }
        if (includeTrace) {
            body.put("options", Map.of("rewrite", true, "includeTrace", true));
        }
        return webClient.post()
                .uri("/api/rag/search")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-tenant-id", tid)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(response -> parseSearchResponse(response, query))
                .doOnError(e -> log.error("[RagClient] 检索失败: {}", e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    static RagSearchResult parseSearchResponse(Map<String, Object> response, String query) {
        Object payload = response.get("data");
        if (!(payload instanceof Map<?, ?> map)) {
            return new RagSearchResult(List.of(), query, List.of());
        }
        String effectiveQuery = map.get("effectiveQuery") != null
                ? map.get("effectiveQuery").toString()
                : query;
        List<?> rawList = (List<?>) map.get("results");
        List<RagHit> hits = parseHitList(rawList, query);
        List<QueryRewriteOutcome> traceOutcomes = parseTraceOutcomes(map.get("trace"));
        log.info("[RagClient] 检索完成: query='{}', 命中 {} 条",
                query != null && query.length() > 30 ? query.substring(0, 30) + "..." : query,
                hits.size());
        return new RagSearchResult(hits, effectiveQuery, traceOutcomes);
    }

    @SuppressWarnings("unchecked")
    private static List<RagHit> parseHitList(List<?> rawList, String query) {
        if (rawList == null || rawList.isEmpty()) {
            return List.of();
        }
        List<RagHit> results = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> hitMap) {
                results.add(parseHit(hitMap));
            } else {
                results.add(new RagHit("未知文档", item.toString(), 0f));
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    static List<QueryRewriteOutcome> parseTraceOutcomes(Object traceObj) {
        if (!(traceObj instanceof Map<?, ?> trace)) {
            return List.of();
        }
        Object stagesObj = trace.get("stages");
        if (!(stagesObj instanceof List<?> stages)) {
            return List.of();
        }
        List<QueryRewriteOutcome> outcomes = new ArrayList<>();
        for (Object stageObj : stages) {
            if (!(stageObj instanceof Map<?, ?> stage)) {
                continue;
            }
            String name = stage.get("name") != null ? stage.get("name").toString() : "";
            if (!List.of("rag", "hyde", "empty-recall").contains(name)) {
                continue;
            }
            long latencyMs = stage.get("latencyMs") instanceof Number n ? n.longValue() : 0L;
            String from = stage.get("from") != null ? stage.get("from").toString() : "";
            String to = stage.get("to") != null ? stage.get("to").toString() : from;
            boolean applied = stage.get("applied") instanceof Boolean b ? b : false;
            String scenarioLabel = stage.get("scenarioLabel") != null ? stage.get("scenarioLabel").toString() : null;
            if ("empty-recall".equals(name) && applied && to.contains("；")) {
                outcomes.add(QueryRewriteOutcome.emptyRecall(from, List.of(to.split("；")), latencyMs, scenarioLabel));
            } else if (applied) {
                outcomes.add(QueryRewriteOutcome.of(name, from, to, latencyMs, scenarioLabel));
            } else {
                outcomes.add(QueryRewriteOutcome.skipped(name, from, latencyMs, scenarioLabel));
            }
        }
        return outcomes;
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

    public record RagSearchResult(
            List<RagHit> hits,
            String effectiveQuery,
            List<QueryRewriteOutcome> traceOutcomes) {
    }
}
