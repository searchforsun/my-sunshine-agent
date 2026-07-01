package com.sunshine.rag.service;

import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.admin.debug.RetrievalDebugResult;
import com.sunshine.rag.admin.debug.RetrievalDebugStage;
import com.sunshine.rag.config.RagChunkProperties;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.metrics.RagSearchMetrics;
import com.sunshine.rag.model.RetrievalCandidate;
import com.sunshine.rag.model.SearchStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
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
    private final RagChunkProperties chunkProperties;
    private final RagSearchMetrics searchMetrics;

    public Mono<List<DocFragment>> search(String query, int topK, String strategyOverride) {
        return search(query, topK, strategyOverride, "default");
    }

    public Mono<List<DocFragment>> search(String query, int topK, String strategyOverride, String tenantId) {
        return search(query, topK, strategyOverride, tenantId, "default");
    }

    public Mono<List<DocFragment>> search(
            String query, int topK, String strategyOverride, String tenantId, String kbId) {
        return search(query, topK, strategyOverride, tenantId, kbId,
                EffectiveRagConfig.fromNacos(searchProperties, rerankProperties, chunkProperties));
    }

    public Mono<List<DocFragment>> search(
            String query,
            int topK,
            String strategyOverride,
            String tenantId,
            String kbId,
            EffectiveRagConfig config) {
        String resolvedStrategy = strategyOverride != null && !strategyOverride.isBlank()
                ? strategyOverride
                : config.strategy();
        SearchStrategy strategy = SearchStrategy.from(resolvedStrategy, searchProperties.defaultStrategy());
        String strategyTag = strategy.metricTag();
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        String kid = kbId != null && !kbId.isBlank() ? kbId.strip() : "default";
        long start = System.nanoTime();
        log.info("[RAG] 检索: tenant={}, kb={}, query='{}', topK={}, strategy={}",
                tid, kid, query, topK, strategy);
        return executeSearch(query, topK, strategy, tid, kid, config)
                .doOnSuccess(results -> searchMetrics.recordSuccess(strategyTag, start, results.size()))
                .doOnError(e -> searchMetrics.recordError(strategyTag, start));
    }

    /** 调试瀑布：返回 vector/bm25/rrf/rerank/filter 各阶段候选 */
    public Mono<RetrievalDebugResult> searchDebug(
            String query,
            int topK,
            String strategyOverride,
            String tenantId,
            String kbId,
            EffectiveRagConfig config) {
        String resolvedStrategy = strategyOverride != null && !strategyOverride.isBlank()
                ? strategyOverride
                : config.strategy();
        SearchStrategy strategy = SearchStrategy.from(resolvedStrategy, searchProperties.defaultStrategy());
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        String kid = kbId != null && !kbId.isBlank() ? kbId.strip() : "default";
        return switch (strategy) {
            case VECTOR -> vectorSearchDebug(query, topK, tid, kid, config);
            case HYBRID -> hybridSearchDebug(query, topK, false, tid, kid, config);
            case HYBRID_RERANK -> hybridSearchDebug(query, topK, true, tid, kid, config);
        };
    }

    private Mono<RetrievalDebugResult> vectorSearchDebug(
            String query, int topK, String tenantId, String kbId, EffectiveRagConfig config) {
        long vectorStart = System.nanoTime();
        return vectorSearchService.search(query, topK, false, tenantId, kbId, config.minScore())
                .map(raw -> {
                    long vectorMs = elapsedMs(vectorStart);
                    List<RetrievalDebugStage> stages = new ArrayList<>();
                    stages.add(RetrievalDebugStage.retrieval("vector", raw, null, vectorMs));
                    FilterOutcome filtered = splitByMinScore(raw, topK, config);
                    stages.add(RetrievalDebugStage.retrieval(
                            "filter", filtered.kept(), filtered.dropped(), 0));
                    return new RetrievalDebugResult(stages, toFragments(filtered.kept()));
                });
    }

    private Mono<RetrievalDebugResult> hybridSearchDebug(
            String query, int topK, boolean rerank, String tenantId, String kbId, EffectiveRagConfig config) {
        int pool = Math.max(config.hybridPoolSize(), rerankProperties.getInputSize());
        long vectorStart = System.nanoTime();
        Mono<List<RetrievalCandidate>> vectorMono = vectorSearchService.search(
                query, pool, false, tenantId, kbId, config.minScore());
        long bm25Start = System.nanoTime();
        Mono<List<RetrievalCandidate>> bm25Mono = bm25SearchService.search(query, pool, tenantId, kbId);
        return Mono.zip(vectorMono, bm25Mono)
                .flatMap(tuple -> {
                    List<RetrievalCandidate> vectorHits = tuple.getT1();
                    List<RetrievalCandidate> bm25Hits = tuple.getT2();
                    long vectorMs = elapsedMs(vectorStart);
                    long bm25Ms = elapsedMs(bm25Start);
                    List<RetrievalDebugStage> stages = new ArrayList<>();
                    stages.add(RetrievalDebugStage.retrieval("vector", vectorHits, null, vectorMs));
                    stages.add(RetrievalDebugStage.retrieval("bm25", bm25Hits, null, bm25Ms));
                    if (bm25Hits.isEmpty() && !bm25SearchService.isEnabled()) {
                        log.warn("[RAG] BM25 未启用，hybrid debug 降级为 vector");
                    }
                    long rrfStart = System.nanoTime();
                    List<RetrievalCandidate> fused = hybridRetrievalService.fuse(
                            List.of(vectorHits, bm25Hits), pool, config.rrfK());
                    List<RetrievalCandidate> scored = hybridRetrievalService.assignDisplayScores(
                            fused, vectorHits, bm25Hits);
                    stages.add(RetrievalDebugStage.retrieval("rrf", scored, null, elapsedMs(rrfStart)));
                    List<RetrievalCandidate> working = scored;
                    if (rerank && rerankService.isEnabled()) {
                        float maxVector = vectorHits.stream()
                                .map(RetrievalCandidate::score)
                                .max(Float::compare)
                                .orElse(0f);
                        if (maxVector < config.minScore()) {
                            stages.add(RetrievalDebugStage.retrieval("rerank", List.of(), null, 0));
                            FilterOutcome filtered = splitByMinScore(List.of(), topK, config);
                            stages.add(RetrievalDebugStage.retrieval(
                                    "filter", filtered.kept(), filtered.dropped(), 0));
                            return Mono.just(new RetrievalDebugResult(stages, List.of()));
                        }
                        int rerankIn = Math.min(scored.size(), rerankProperties.getInputSize());
                        List<RetrievalCandidate> input = scored.subList(0, rerankIn);
                        long rerankStart = System.nanoTime();
                        return rerankService.rerank(query, input, topK)
                                .map(ranked -> {
                                    stages.add(RetrievalDebugStage.retrieval(
                                            "rerank", ranked, null, elapsedMs(rerankStart)));
                                    FilterOutcome filtered = splitByMinScore(ranked, topK, config);
                                    stages.add(RetrievalDebugStage.retrieval(
                                            "filter", filtered.kept(), filtered.dropped(), 0));
                                    return new RetrievalDebugResult(stages, toFragments(filtered.kept()));
                                });
                    }
                    FilterOutcome filtered = splitByMinScore(working, topK, config);
                    stages.add(RetrievalDebugStage.retrieval(
                            "filter", filtered.kept(), filtered.dropped(), 0));
                    return Mono.just(new RetrievalDebugResult(stages, toFragments(filtered.kept())));
                });
    }

    private FilterOutcome splitByMinScore(
            List<RetrievalCandidate> candidates, int topK, EffectiveRagConfig config) {
        List<RetrievalCandidate> kept = new ArrayList<>();
        List<RetrievalCandidate> dropped = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            float threshold = thresholdFor(candidate, config.minScore(), config.rerankMinScore());
            if (candidate.score() >= threshold) {
                kept.add(candidate);
            } else {
                dropped.add(candidate);
            }
        }
        if (kept.size() > topK) {
            dropped.addAll(kept.subList(topK, kept.size()));
            kept = List.copyOf(kept.subList(0, topK));
        }
        return new FilterOutcome(kept, dropped);
    }

    private static long elapsedMs(long startNano) {
        return Math.max(0, (System.nanoTime() - startNano) / 1_000_000);
    }

    private record FilterOutcome(List<RetrievalCandidate> kept, List<RetrievalCandidate> dropped) {
    }

    private Mono<List<DocFragment>> executeSearch(
            String query, int topK, SearchStrategy strategy, String tenantId, String kbId, EffectiveRagConfig config) {
        return switch (strategy) {
            case VECTOR -> vectorSearch(query, topK, tenantId, kbId, config);
            case HYBRID -> hybridSearch(query, topK, false, tenantId, kbId, config);
            case HYBRID_RERANK -> hybridSearch(query, topK, true, tenantId, kbId, config);
        };
    }

    private Mono<List<DocFragment>> vectorSearch(
            String query, int topK, String tenantId, String kbId, EffectiveRagConfig config) {
        return vectorSearchService.search(query, topK, true, tenantId, kbId, config.minScore())
                .map(this::toFragments)
                .doOnSuccess(r -> log.info("[RAG] 有效命中: {} 条", r.size()));
    }

    private Mono<List<DocFragment>> hybridSearch(
            String query, int topK, boolean rerank, String tenantId, String kbId, EffectiveRagConfig config) {
        int pool = Math.max(config.hybridPoolSize(), rerankProperties.getInputSize());
        Mono<List<RetrievalCandidate>> vectorMono = vectorSearchService.search(
                query, pool, false, tenantId, kbId, config.minScore());
        Mono<List<RetrievalCandidate>> bm25Mono = bm25SearchService.search(query, pool, tenantId, kbId);
        return Mono.zip(vectorMono, bm25Mono)
                .flatMap(tuple -> {
                    List<RetrievalCandidate> vectorHits = tuple.getT1();
                    List<RetrievalCandidate> bm25Hits = tuple.getT2();
                    if (bm25Hits.isEmpty() && !bm25SearchService.isEnabled()) {
                        log.warn("[RAG] BM25 未启用，hybrid 降级为 vector");
                    }
                    List<RetrievalCandidate> fused = hybridRetrievalService.fuse(
                            List.of(vectorHits, bm25Hits), pool, config.rrfK());
                    List<RetrievalCandidate> scored = hybridRetrievalService.assignDisplayScores(
                            fused, vectorHits, bm25Hits);
                    if (!rerank || !rerankService.isEnabled()) {
                        return Mono.just(applyMinScore(scored, topK, config));
                    }
                    float maxVector = vectorHits.stream()
                            .map(RetrievalCandidate::score)
                            .max(Float::compare)
                            .orElse(0f);
                    float vectorFloor = config.minScore();
                    if (maxVector < vectorFloor) {
                        log.info("[RAG] 向量锚点未达阈: maxVector={} < {}, hybrid+rerank 空召回",
                                maxVector, vectorFloor);
                        searchMetrics.recordVectorAnchorEmpty();
                        return Mono.just(List.<RetrievalCandidate>of());
                    }
                    int rerankIn = Math.min(scored.size(), rerankProperties.getInputSize());
                    List<RetrievalCandidate> input = scored.subList(0, rerankIn);
                    return rerankService.rerank(query, input, topK)
                            .map(ranked -> applyMinScore(ranked, topK, config));
                })
                .map(this::toFragments)
                .doOnSuccess(r -> log.info("[RAG] hybrid 有效命中: {} 条", r.size()));
    }

    private List<RetrievalCandidate> applyMinScore(List<RetrievalCandidate> candidates, int topK, EffectiveRagConfig config) {
        return candidates.stream()
                .filter(c -> c.score() >= thresholdFor(c, config.minScore(), config.rerankMinScore()))
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
