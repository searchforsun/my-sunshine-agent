package com.sunshine.orchestrator.rag;

import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.config.RagSearchProperties;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索编排：rag 前置改写 + 可选 HyDE + 首次检索 + empty-recall 二次检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final RagClient ragClient;
    private final RagSearchProperties ragSearchProperties;
    private final QueryRewriteService queryRewriteService;

    public List<RagClient.RagHit> search(String query, int topK) {
        return search(query, topK, null);
    }

    public List<RagClient.RagHit> search(String query, int topK, String traceMessageId) {
        return searchMono(query, topK, traceMessageId).blockOptional().orElse(List.of());
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, int topK) {
        return searchMono(query, topK, null);
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, int topK, String traceMessageId) {
        return prepareSearch(query, traceMessageId)
                .flatMap(prep -> {
                    String strategy = ragSearchProperties.getStrategy();
                    return ragClient.search(prep.searchQuery(), topK, strategy)
                            .flatMap(first -> {
                                if (!first.isEmpty() || !queryRewriteService.isEmptyRecallEnabled()) {
                                    return Mono.just(first);
                                }
                                return retryWithRewrite(
                                        prep.emptyRecallBase(), topK, strategy, first, traceMessageId);
                            });
                });
    }

    /** rag 改写 + 可选 HyDE 假想文档 → 检索 query；empty-recall 仍基于改写 query */
    private Mono<SearchPrep> prepareSearch(String query, String traceMessageId) {
        return Mono.fromCallable(() -> {
                    String emptyRecallBase = query;
                    if (queryRewriteService.isRagEnabled()) {
                        emptyRecallBase = queryRewriteService.rewriteForRag(query, traceMessageId).effectiveQuery();
                    }
                    String searchQuery = emptyRecallBase;
                    if (queryRewriteService.isHydeEnabled()) {
                        QueryRewriteOutcome hyde =
                                queryRewriteService.hydeForRag(query, traceMessageId);
                        if (hyde.applied()) {
                            searchQuery = hyde.rewrittenQuery();
                        }
                    }
                    return new SearchPrep(searchQuery, emptyRecallBase);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private record SearchPrep(String searchQuery, String emptyRecallBase) {}

    private Mono<List<RagClient.RagHit>> retryWithRewrite(
            String originalQuery, int topK, String strategy, List<RagClient.RagHit> emptyFirst, String traceMessageId) {
        return Mono.fromCallable(() -> queryRewriteService.rewriteEmptyRecall(originalQuery, traceMessageId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    List<String> alternatives = result.alternatives();
                    if (alternatives.isEmpty()) {
                        return Mono.just(emptyFirst);
                    }
                    log.info("[KnowledgeRetrieval] empty-recall 二次检索: alts={}", alternatives);
                    return Flux.fromIterable(alternatives)
                            .flatMap(alt -> ragClient.search(alt, topK, strategy))
                            .collectList()
                            .map(batchLists -> mergeHits(batchLists, topK));
                });
    }

    static List<RagClient.RagHit> mergeHits(List<List<RagClient.RagHit>> batchLists, int topK) {
        Map<String, RagClient.RagHit> merged = new LinkedHashMap<>();
        for (List<RagClient.RagHit> hits : batchLists) {
            for (RagClient.RagHit hit : hits) {
                merged.merge(dedupeKey(hit), hit,
                        (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        List<RagClient.RagHit> sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparing(RagClient.RagHit::score).reversed());
        if (sorted.size() <= topK) {
            return sorted;
        }
        return List.copyOf(sorted.subList(0, topK));
    }

    private static String dedupeKey(RagClient.RagHit hit) {
        String content = hit.content() != null ? hit.content() : "";
        String prefix = content.length() > 80 ? content.substring(0, 80) : content;
        return hit.docName() + "|" + prefix;
    }
}
