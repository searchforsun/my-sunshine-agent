package com.sunshine.rag.service;

import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.metrics.RagSearchMetrics;
import com.sunshine.rag.model.RetrievalCandidate;
import com.sunshine.rag.model.SearchStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * RAG 检索编排：vector / hybrid / hybrid+rerank。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final VectorSearchService vectorSearchService;
    private final Bm25SearchService bm25SearchService;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final RagSearchProperties searchProperties;
    private final RagRerankProperties rerankProperties;
    private final RagSearchMetrics searchMetrics;

    public Mono<List<DocFragment>> search(String query, int topK, String strategyOverride) {
        return search(query, topK, strategyOverride, "default");
    }

    public Mono<List<DocFragment>> search(String query, int topK, String strategyOverride, String tenantId) {
        SearchStrategy strategy = SearchStrategy.from(strategyOverride, searchProperties.defaultStrategy());
        String strategyTag = strategy.metricTag();
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        long start = System.nanoTime();
        log.info("[RAG] 检索: tenant={}, query='{}', topK={}, strategy={}", tid, query, topK, strategy);
        return executeSearch(query, topK, strategy, tid)
                .doOnSuccess(results -> searchMetrics.recordSuccess(strategyTag, start, results.size()))
                .doOnError(e -> searchMetrics.recordError(strategyTag, start));
    }

    private Mono<List<DocFragment>> executeSearch(String query, int topK, SearchStrategy strategy, String tenantId) {
        return switch (strategy) {
            case VECTOR -> vectorSearch(query, topK, tenantId);
            case HYBRID -> hybridSearch(query, topK, false, tenantId);
            case HYBRID_RERANK -> hybridSearch(query, topK, true, tenantId);
        };
    }

    private Mono<List<DocFragment>> vectorSearch(String query, int topK, String tenantId) {
        return vectorSearchService.search(query, topK, true, tenantId)
                .map(this::toFragments)
                .doOnSuccess(r -> log.info("[RAG] 有效命中: {} 条", r.size()));
    }

    private Mono<List<DocFragment>> hybridSearch(String query, int topK, boolean rerank, String tenantId) {
        int pool = Math.max(searchProperties.getHybridPoolSize(), rerankProperties.getInputSize());
        Mono<List<RetrievalCandidate>> vectorMono = vectorSearchService.search(query, pool, false, tenantId);
        Mono<List<RetrievalCandidate>> bm25Mono = bm25SearchService.search(query, pool, tenantId);
        return Mono.zip(vectorMono, bm25Mono)
                .flatMap(tuple -> {
                    List<RetrievalCandidate> vectorHits = tuple.getT1();
                    List<RetrievalCandidate> bm25Hits = tuple.getT2();
                    if (bm25Hits.isEmpty() && !bm25SearchService.isEnabled()) {
                        log.warn("[RAG] BM25 未启用，hybrid 降级为 vector");
                    }
                    List<RetrievalCandidate> fused = hybridRetrievalService.fuse(
                            List.of(vectorHits, bm25Hits), pool);
                    List<RetrievalCandidate> scored = hybridRetrievalService.assignDisplayScores(
                            fused, vectorHits, bm25Hits);
                    if (!rerank || !rerankService.isEnabled()) {
                        return Mono.just(applyMinScore(scored, topK));
                    }
                    float maxVector = vectorHits.stream()
                            .map(RetrievalCandidate::score)
                            .max(Float::compare)
                            .orElse(0f);
                    float vectorFloor = searchProperties.getMinScore();
                    if (maxVector < vectorFloor) {
                        log.info("[RAG] 向量锚点未达阈: maxVector={} < {}, hybrid+rerank 空召回",
                                maxVector, vectorFloor);
                        searchMetrics.recordVectorAnchorEmpty();
                        return Mono.just(List.<RetrievalCandidate>of());
                    }
                    int rerankIn = Math.min(scored.size(), rerankProperties.getInputSize());
                    List<RetrievalCandidate> input = scored.subList(0, rerankIn);
                    return rerankService.rerank(query, input, topK)
                            .map(ranked -> applyMinScore(ranked, topK));
                })
                .map(this::toFragments)
                .doOnSuccess(r -> log.info("[RAG] hybrid 有效命中: {} 条", r.size()));
    }

    private List<RetrievalCandidate> applyMinScore(List<RetrievalCandidate> candidates, int topK) {
        float vectorMin = searchProperties.getMinScore();
        float rerankMin = rerankProperties.getMinScore();
        return candidates.stream()
                .filter(c -> c.score() >= thresholdFor(c, vectorMin, rerankMin))
                .limit(topK)
                .toList();
    }

    private static float thresholdFor(RetrievalCandidate c, float vectorMin, float rerankMin) {
        if (RetrievalCandidate.SOURCE_RERANK.equals(c.source())) {
            return rerankMin;
        }
        return vectorMin;
    }

    private List<DocFragment> toFragments(List<RetrievalCandidate> candidates) {
        return candidates.stream()
                .map(c -> new DocFragment(c.docName(), c.content(), c.score()))
                .toList();
    }

    public record DocFragment(String docName, String content, float score) {
    }
}
