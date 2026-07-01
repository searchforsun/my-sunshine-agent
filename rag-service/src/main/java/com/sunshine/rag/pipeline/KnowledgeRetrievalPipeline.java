package com.sunshine.rag.pipeline;

import com.sunshine.rag.config.RagRewriteProperties;
import com.sunshine.rag.service.RetrievalService;
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
 * 知识库检索 pipeline：rag 改写 → 检索 → HyDE fallback → empty-recall。
 * ADR-002 SSOT。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalPipeline {
    private final RetrievalService retrievalService;
    private final QueryRewritePipeline queryRewritePipeline;
    private final RagRewriteProperties rewriteProperties;

    public Mono<PipelineSearchResult> search(PipelineSearchRequest request) {
        if (request.query().isBlank()) {
            return Mono.just(emptyResult(request, request.query()));
        }
        RetrievalTrace trace = request.includeTrace() ? new RetrievalTrace() : null;
        return resolveInitialSearchQuery(request, trace)
                .flatMap(searchQuery -> executeRetrieval(request, searchQuery, trace)
                        .flatMap(first -> {
                            if (!first.isEmpty()) {
                                return Mono.just(buildResult(request, searchQuery, first, trace));
                            }
                            if (!request.rewrite()) {
                                return Mono.just(buildResult(request, searchQuery, first, trace));
                            }
                            return tryHydeFallback(request, trace)
                                    .flatMap(hits -> {
                                        if (!hits.isEmpty()) {
                                            return Mono.just(buildResult(request, searchQuery, hits, trace));
                                        }
                                        return retryWithEmptyRecall(request, trace);
                                    });
                        }));
    }

    private Mono<String> resolveInitialSearchQuery(PipelineSearchRequest request, RetrievalTrace trace) {
        if (!request.rewrite() || !queryRewritePipeline.isRagEnabled()) {
            return Mono.just(request.query());
        }
        return Mono.fromCallable(() -> {
                    QueryRewriteOutcome outcome = queryRewritePipeline.rewriteForRag(request.query());
                    if (trace != null) {
                        trace.addStage(toTraceStage(outcome));
                    }
                    return outcome.effectiveQuery();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<List<RetrievalService.DocFragment>> executeRetrieval(
            PipelineSearchRequest request, String searchQuery, RetrievalTrace trace) {
        if (trace != null) {
            trace.incrementSearchCount();
        }
        return retrievalService.search(
                searchQuery, request.topK(), request.strategy(), request.tenantId());
    }

    private Mono<List<RetrievalService.DocFragment>> tryHydeFallback(
            PipelineSearchRequest request, RetrievalTrace trace) {
        if (!queryRewritePipeline.isHydeEnabled()) {
            return retryWithEmptyRecallFragments(request, trace, List.of());
        }
        return Mono.fromCallable(() -> queryRewritePipeline.hydeForRag(request.query()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(hyde -> {
                    if (trace != null) {
                        trace.addStage(toTraceStage(hyde));
                    }
                    String hydeDoc = hyde.applied() ? hyde.rewrittenQuery() : null;
                    if (hydeDoc == null || hydeDoc.isBlank()) {
                        return retryWithEmptyRecallFragments(request, trace, List.of());
                    }
                    log.info("[KnowledgeRetrieval] HyDE fallback 检索");
                    return executeRetrieval(request, hydeDoc, trace)
                            .flatMap(hydeHits -> {
                                if (!hydeHits.isEmpty()) {
                                    return Mono.just(hydeHits);
                                }
                                return retryWithEmptyRecallFragments(request, trace, List.of());
                            });
                });
    }

    private Mono<PipelineSearchResult> retryWithEmptyRecall(
            PipelineSearchRequest request, RetrievalTrace trace) {
        return retryWithEmptyRecallFragments(request, trace, List.of())
                .map(hits -> buildResult(request, resolveEffectiveFromTrace(trace, request.query()), hits, trace));
    }

    private Mono<List<RetrievalService.DocFragment>> retryWithEmptyRecallFragments(
            PipelineSearchRequest request, RetrievalTrace trace, List<RetrievalService.DocFragment> emptyFirst) {
        if (!queryRewritePipeline.isEmptyRecallEnabled()) {
            return Mono.just(emptyFirst);
        }
        return Mono.fromCallable(() -> queryRewritePipeline.rewriteEmptyRecall(request.query()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    if (trace != null) {
                        trace.addStage(toTraceStage(result.outcome()));
                    }
                    List<String> alternatives = result.alternatives();
                    if (alternatives.isEmpty()) {
                        return Mono.just(emptyFirst);
                    }
                    log.info("[KnowledgeRetrieval] empty-recall 二次检索: alts={}", alternatives);
                    return Flux.fromIterable(alternatives)
                            .flatMap(alt -> executeRetrieval(request, alt, trace))
                            .collectList()
                            .map(batchLists -> mergeFragments(batchLists, request.topK()));
                });
    }

    static List<RetrievalService.DocFragment> mergeFragments(
            List<List<RetrievalService.DocFragment>> batchLists, int topK) {
        Map<String, RetrievalService.DocFragment> merged = new LinkedHashMap<>();
        for (List<RetrievalService.DocFragment> hits : batchLists) {
            for (RetrievalService.DocFragment hit : hits) {
                merged.merge(dedupeKey(hit), hit,
                        (a, b) -> a.score() >= b.score() ? a : b);
            }
        }
        List<RetrievalService.DocFragment> sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparing(RetrievalService.DocFragment::score).reversed());
        if (sorted.size() <= topK) {
            return sorted;
        }
        return List.copyOf(sorted.subList(0, topK));
    }

    private static String dedupeKey(RetrievalService.DocFragment hit) {
        String content = hit.content() != null ? hit.content() : "";
        String prefix = content.length() > 80 ? content.substring(0, 80) : content;
        return hit.docName() + "|" + prefix;
    }

    private RetrievalStage toTraceStage(QueryRewriteOutcome outcome) {
        String label = rewriteProperties.timelineOrDefault().labelFor(outcome.scenario());
        return outcome.toStage(label);
    }

    private static String resolveEffectiveFromTrace(RetrievalTrace trace, String fallback) {
        if (trace == null) {
            return fallback;
        }
        for (RetrievalStage stage : trace.stages()) {
            if ("rag".equals(stage.name()) && Boolean.TRUE.equals(stage.applied()) && stage.to() != null) {
                return stage.to();
            }
        }
        return fallback;
    }

    private PipelineSearchResult buildResult(
            PipelineSearchRequest request,
            String effectiveQuery,
            List<RetrievalService.DocFragment> results,
            RetrievalTrace trace) {
        return new PipelineSearchResult(
                request.query(),
                effectiveQuery,
                results != null ? results : List.of(),
                trace);
    }

    private PipelineSearchResult emptyResult(PipelineSearchRequest request, String effectiveQuery) {
        return new PipelineSearchResult(request.query(), effectiveQuery, List.of(), null);
    }
}
