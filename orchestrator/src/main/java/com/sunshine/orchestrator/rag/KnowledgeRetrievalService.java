package com.sunshine.orchestrator.rag;

import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 知识库检索 — 薄封装 RagClient pipeline（ADR-002）；topK 未指定时由 rag-service Nacos 决定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final RagClient ragClient;
    private final DefaultKbResolver defaultKbResolver;

    public List<RagClient.RagHit> search(String query) {
        return search(query, null, "default", null);
    }

    public List<RagClient.RagHit> search(String query, String traceMessageId) {
        return search(query, null, "default", traceMessageId);
    }

    public List<RagClient.RagHit> search(String query, String kbId, String tenantId, String traceMessageId) {
        return searchMono(query, null, kbId, tenantId, traceMessageId).blockOptional().orElse(List.of());
    }

    public List<RagClient.RagHit> search(String query, int topK) {
        return search(query, topK, null, "default", null);
    }

    public List<RagClient.RagHit> search(String query, int topK, String traceMessageId) {
        return search(query, topK, null, "default", traceMessageId);
    }

    public List<RagClient.RagHit> search(String query, int topK, String tenantId, String traceMessageId) {
        return search(query, topK, null, tenantId, traceMessageId);
    }

    public List<RagClient.RagHit> search(String query, int topK, String kbId, String tenantId, String traceMessageId) {
        return searchMono(query, topK, kbId, tenantId, traceMessageId).blockOptional().orElse(List.of());
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query) {
        return searchMono(query, null, null, "default", null);
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, String traceMessageId) {
        return searchMono(query, null, null, "default", traceMessageId);
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, int topK) {
        return searchMono(query, topK, null, "default", null);
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, Integer topK, String traceMessageId) {
        return searchMono(query, topK, null, "default", traceMessageId);
    }

    public Mono<List<RagClient.RagHit>> searchMono(String query, Integer topK, String tenantId, String traceMessageId) {
        return searchMono(query, topK, null, tenantId, traceMessageId);
    }

    public Mono<List<RagClient.RagHit>> searchMono(
            String query, Integer topK, String kbId, String tenantId, String traceMessageId) {
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        boolean includeTrace = traceMessageId != null && !traceMessageId.isBlank();
        return defaultKbResolver.resolve(tid, kbId)
                .flatMap(resolvedKb -> ragClient.searchKnowledge(query, topK, tid, resolvedKb, null, includeTrace)
                        .doOnNext(result -> recordTrace(traceMessageId, result))
                        .map(RagClient.RagSearchResult::hits));
    }

    private static void recordTrace(String traceMessageId, RagClient.RagSearchResult result) {
        if (traceMessageId == null || traceMessageId.isBlank() || result.traceOutcomes() == null) {
            return;
        }
        for (var outcome : result.traceOutcomes()) {
            QueryRewriteTrace.record(traceMessageId, outcome);
        }
    }
}
