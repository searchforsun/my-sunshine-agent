package com.sunshine.orchestrator.rag;

import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import reactor.core.publisher.Mono;

import java.util.List;

/** kb 解析 + RagClient pipeline 调用 + 改写 trace 落盘（ADR-002 编排侧唯一入口） */
public final class RagSearch {

    private RagSearch() {
    }

    public static Mono<List<RagClient.RagHit>> searchMono(
            RagClient ragClient,
            DefaultKbResolver kbResolver,
            String query,
            Integer topK,
            String kbId,
            String tenantId,
            String traceMessageId) {
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        boolean includeTrace = traceMessageId != null && !traceMessageId.isBlank();
        return kbResolver.resolve(tid, kbId)
                .flatMap(resolvedKb -> ragClient.searchKnowledge(query, topK, tid, resolvedKb, null, includeTrace)
                        .doOnNext(result -> recordTrace(traceMessageId, result))
                        .map(RagClient.RagSearchResult::hits));
    }

    public static List<RagClient.RagHit> searchBlocking(
            RagClient ragClient,
            DefaultKbResolver kbResolver,
            String query,
            Integer topK,
            String kbId,
            String tenantId,
            String traceMessageId) {
        return searchMono(ragClient, kbResolver, query, topK, kbId, tenantId, traceMessageId)
                .blockOptional()
                .orElse(List.of());
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
