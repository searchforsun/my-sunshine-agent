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
 * 知识库检索编排：rag 前置改写 → 首次检索 → HyDE fallback → empty-recall 二次检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final RagClient ragClient;
    private final RagSearchProperties ragSearchProperties;
    private final QueryRewriteService queryRewriteService;

    public List<RagClient.RagHit> search(String query, int topK) {
        return search(query, topK, "default", null);
    }

    public List<RagClient.RagHit> search(String query, int topK, String traceMessageId) {
        return search(query, topK, "default", traceMessageId);
    }

    public List<RagClient.RagHit> search(String query, int topK, String tenantId, String traceMessageId) {
        return searchMono(query, topK, tenantId, traceMessageId).blockOptional().orElse(List.of());
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, int topK) {
        return searchMono(query, topK, "default", null);
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, int topK, String traceMessageId) {
        return searchMono(query, topK, "default", traceMessageId);
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, int topK, String tenantId, String traceMessageId) {
        String originalQuery = query != null ? query.strip() : "";
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        return resolveInitialSearchQuery(originalQuery, traceMessageId)
                .flatMap(searchQuery -> {
                    String strategy = ragSearchProperties.getStrategy();
                    return ragClient.search(searchQuery, topK, strategy, tid)
                            .flatMap(first -> {
                                if (!first.isEmpty()) {
                                    return Mono.just(first);
                                }
                                return tryHydeFallback(originalQuery, topK, strategy, tid, traceMessageId);
                            });
                });
    }

    /** rag 改写后的首次检索 query（HyDE 不在此阶段覆盖） */
    private Mono<String> resolveInitialSearchQuery(String query, String traceMessageId) {
        return Mono.fromCallable(() -> {
                    if (queryRewriteService.isRagEnabled()) {
                        return queryRewriteService.rewriteForRag(query, traceMessageId).effectiveQuery();
                    }
                    return query;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 首次 0 命中且 HyDE 开启时，用假想文档再检一次 */
    private Mono<List<RagClient.RagHit>> tryHydeFallback(
            String originalQuery, int topK, String strategy, String tenantId, String traceMessageId) {
        if (!queryRewriteService.isHydeEnabled()) {
            return retryWithRewrite(originalQuery, topK, strategy, tenantId, List.of(), traceMessageId);
        }
        return Mono.fromCallable(() -> queryRewriteService.hydeForRag(originalQuery, traceMessageId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(hyde -> {
                    String hydeDoc = hyde.applied() ? hyde.rewrittenQuery() : null;
                    if (hydeDoc == null || hydeDoc.isBlank()) {
                        return retryWithRewrite(originalQuery, topK, strategy, tenantId, List.of(), traceMessageId);
                    }
                    log.info("[KnowledgeRetrieval] HyDE fallback 检索");
                    return ragClient.search(hydeDoc, topK, strategy, tenantId)
                            .flatMap(hydeHits -> {
                                if (!hydeHits.isEmpty()) {
                                    return Mono.just(hydeHits);
                                }
                                return retryWithRewrite(originalQuery, topK, strategy, tenantId, List.of(), traceMessageId);
                            });
                });
    }

    private Mono<List<RagClient.RagHit>> retryWithRewrite(
            String originalQuery, int topK, String strategy, String tenantId,
            List<RagClient.RagHit> emptyFirst, String traceMessageId) {
        if (!queryRewriteService.isEmptyRecallEnabled()) {
            return Mono.just(emptyFirst);
        }
        return Mono.fromCallable(() -> queryRewriteService.rewriteEmptyRecall(originalQuery, traceMessageId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    List<String> alternatives = result.alternatives();
                    if (alternatives.isEmpty()) {
                        return Mono.just(emptyFirst);
                    }
                    log.info("[KnowledgeRetrieval] empty-recall 二次检索: alts={}", alternatives);
                    return Flux.fromIterable(alternatives)
                            .flatMap(alt -> ragClient.search(alt, topK, strategy, tenantId))
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
