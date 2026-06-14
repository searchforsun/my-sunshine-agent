package com.sunshine.rag.service;

import com.sunshine.rag.config.RagSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * RAG 检索编排服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final RagSearchProperties searchProperties;

    public Mono<List<DocFragment>> search(String query, int topK) {
        log.info("[RAG] 检索: query='{}', topK={}", query, topK);

        return embeddingService.embed(query)
                .map(vector -> {
                    List<MilvusService.SearchHit> raw = milvusService.search(vector, topK);
                    float minScore = searchProperties.getMinScore();
                    List<MilvusService.SearchHit> filtered = SearchScoreFilter.apply(raw, minScore);
                    if (log.isDebugEnabled() && !raw.isEmpty()) {
                        raw.forEach(hit -> log.debug("[RAG] score={} doc={} pass={}",
                                String.format("%.4f", hit.score()),
                                hit.docName(),
                                hit.score() >= minScore));
                    }
                    if (raw.size() != filtered.size()) {
                        log.info("[RAG] 相似度过滤: minScore={}, raw={}, effective={}",
                                minScore, raw.size(), filtered.size());
                    }
                    return filtered.stream()
                            .map(hit -> new DocFragment(hit.docName(), hit.content(), hit.score()))
                            .toList();
                })
                .doOnSuccess(r -> log.info("[RAG] 有效命中: {} 条", r.size()));
    }

    public record DocFragment(String docName, String content, float score) {
    }
}
