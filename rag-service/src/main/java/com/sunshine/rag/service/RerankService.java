package com.sunshine.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.metrics.RagSearchMetrics;
import com.sunshine.rag.model.RetrievalCandidate;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云 Rerank（DashScope gte-rerank）— 对 hybrid 候选重排。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {

    private final RagRerankProperties rerankProperties;
    private final ObjectMapper objectMapper;
    private final RagSearchMetrics searchMetrics;

    @Value("${embedding.api-key:}")
    private String embeddingApiKey;

    private WebClient webClient;

    @PostConstruct
    void init() {
        if (!rerankProperties.isEnabled()) {
            return;
        }
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[RAG-Rerank] 未配置 API Key，rerank 将降级为 RRF 顺序");
            return;
        }
        webClient = WebClient.builder()
                .baseUrl(rerankProperties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        log.info("[RAG-Rerank] enabled model={}", rerankProperties.getModel());
    }

    public boolean isEnabled() {
        return rerankProperties.isEnabled() && webClient != null;
    }

    public Mono<List<RetrievalCandidate>> rerank(String query, List<RetrievalCandidate> candidates, int topN) {
        if (!isEnabled() || candidates.isEmpty()) {
            return Mono.just(candidates.stream().limit(topN).toList());
        }
        List<String> documents = candidates.stream().map(RetrievalCandidate::content).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", rerankProperties.getModel());
        body.put("input", Map.of("query", query, "documents", documents));
        body.put("parameters", Map.of(
                "top_n", documents.size(),
                "return_documents", false));
        long rerankStart = System.nanoTime();
        return webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        return finalizeRerank(parseResults(json, candidates, topN), candidates, topN);
                    } catch (Exception e) {
                        throw new IllegalStateException("rerank parse failed", e);
                    }
                })
                .doOnSuccess(r -> searchMetrics.recordRerank(rerankStart, "success"))
                .doOnError(e -> {
                    log.warn("[RAG-Rerank] 调用失败: {}", e.getMessage());
                    searchMetrics.recordRerank(rerankStart, "error");
                })
                .onErrorReturn(candidates.stream().limit(topN).toList());
    }

    record RerankOutcome(List<RetrievalCandidate> ranked, float topRelevance, boolean apiHadHits) {
    }

    RerankOutcome parseResults(String json, List<RetrievalCandidate> candidates, int topN) throws Exception {
        JsonNode results = objectMapper.readTree(json).path("output").path("results");
        float topRelevance = 0f;
        boolean apiHadHits = results.isArray() && !results.isEmpty();
        if (apiHadHits) {
            topRelevance = (float) results.get(0).path("relevance_score").asDouble(0.0);
        }
        List<RetrievalCandidate> ranked = new ArrayList<>();
        float floor = rerankProperties.getMinRelevance();
        for (JsonNode item : results) {
            float relevance = (float) item.path("relevance_score").asDouble(0.0);
            if (relevance < floor) {
                continue;
            }
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            float normalized = (relevance - floor) / (1f - floor);
            float score = 0.48f + 0.52f * Math.min(1f, Math.max(0f, normalized));
            ranked.add(candidates.get(index)
                    .withScore(score)
                    .withSource(RetrievalCandidate.SOURCE_RERANK));
            if (ranked.size() >= topN) {
                break;
            }
        }
        return new RerankOutcome(ranked, topRelevance, apiHadHits);
    }

    List<RetrievalCandidate> finalizeRerank(
            RerankOutcome outcome, List<RetrievalCandidate> candidates, int topN) {
        if (!outcome.ranked().isEmpty()) {
            log.info("[RAG-Rerank] 重排完成: in={}, out={}", candidates.size(), outcome.ranked().size());
            return outcome.ranked();
        }
        log.info("[RAG-Rerank] 无有效重排结果, 回退 hybrid 池");
        return candidates.stream().limit(topN).toList();
    }

    private String resolveApiKey() {
        if (rerankProperties.getApiKey() != null && !rerankProperties.getApiKey().isBlank()) {
            return rerankProperties.getApiKey();
        }
        return embeddingApiKey;
    }
}
