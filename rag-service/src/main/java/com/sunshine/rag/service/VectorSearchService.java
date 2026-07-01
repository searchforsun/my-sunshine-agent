package com.sunshine.rag.service;

import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.model.RetrievalCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Milvus 向量检索封装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final RagSearchProperties searchProperties;

    public Mono<List<RetrievalCandidate>> search(String query, int topK, boolean applyMinScoreFilter) {
        return search(query, topK, applyMinScoreFilter, "default");
    }

    public Mono<List<RetrievalCandidate>> search(
            String query, int topK, boolean applyMinScoreFilter, String tenantId) {
        return search(query, topK, applyMinScoreFilter, tenantId, "default");
    }

    public Mono<List<RetrievalCandidate>> search(
            String query, int topK, boolean applyMinScoreFilter, String tenantId, String kbId) {
        return search(query, topK, applyMinScoreFilter, tenantId, kbId, searchProperties.getMinScore());
    }

    public Mono<List<RetrievalCandidate>> search(
            String query, int topK, boolean applyMinScoreFilter, String tenantId, String kbId, float minScore) {
        return embeddingService.embed(query)
                .map(vector -> {
                    List<MilvusService.SearchHit> raw = milvusService.search(vector, topK, tenantId, kbId);
                    List<MilvusService.SearchHit> hits = applyMinScoreFilter
                            ? SearchScoreFilter.apply(raw, minScore)
                            : raw;
                    if (applyMinScoreFilter && raw.size() != hits.size()) {
                        log.info("[RAG] 向量相似度过滤: minScore={}, raw={}, effective={}",
                                minScore, raw.size(), hits.size());
                    }
                    return hits.stream()
                            .map(hit -> new RetrievalCandidate(
                                    null,
                                    hit.docName(),
                                    hit.content(),
                                    hit.score(),
                                    RetrievalCandidate.SOURCE_VECTOR))
                            .toList();
                });
    }
}
