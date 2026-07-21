package com.sunshine.rag.pipeline;

import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.admin.config.ResolvedKbConfig;
import com.sunshine.rag.admin.config.RewriteSettings;
import com.sunshine.rag.admin.debug.RetrievalDebugResult;
import com.sunshine.rag.admin.debug.RetrievalDebugStage;
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
    private final EffectiveConfigResolver effectiveConfigResolver;

    public Mono<PipelineSearchResult> search(PipelineSearchRequest request) {
        ResolvedKbConfig resolved = effectiveConfigResolver.resolve(request.tenantId(), request.kbId());
        return searchWithResolved(request, resolved);
    }

    /** smoke eval：仅覆盖检索参数，rewrite 仍用该 kb PRODUCTION */
    public Mono<PipelineSearchResult> searchWithConfig(PipelineSearchRequest request, EffectiveRagConfig config) {
        ResolvedKbConfig production = effectiveConfigResolver.resolve(request.tenantId(), request.kbId());
        ResolvedKbConfig resolved = new ResolvedKbConfig(
                config, production.rewrite(), production.defaultTopK());
        return searchWithResolved(request, resolved);
    }

    public Mono<PipelineSearchResult> searchWithResolved(PipelineSearchRequest request, ResolvedKbConfig resolved) {
        if (request.query().isBlank()) {
            return Mono.just(emptyResult(request, request.query()));
        }
        RetrievalTrace trace = request.includeTrace() ? new RetrievalTrace() : null;
        RewriteSettings rewrite = resolved.rewrite();
        EffectiveRagConfig config = resolved.retrieval();
        return resolveInitialSearchQuery(request, trace, rewrite)
                .flatMap(searchQuery -> executeRetrieval(request, searchQuery, trace, config)
                        .flatMap(first -> {
                            if (!first.isEmpty()) {
                                return Mono.just(buildResult(request, searchQuery, first, trace));
                            }
                            if (!request.rewrite()) {
                                return Mono.just(buildResult(request, searchQuery, first, trace));
                            }
                            return tryHydeFallback(request, trace, config, rewrite)
                                    .flatMap(hits -> {
                                        if (!hits.isEmpty()) {
                                            return Mono.just(buildResult(request, searchQuery, hits, trace));
                                        }
                                        return retryWithEmptyRecall(request, trace, config, rewrite);
                                    });
                        }));
    }

    public Mono<RetrievalDebugResult> debugSearch(PipelineSearchRequest request, ResolvedKbConfig resolved) {
        if (request.query().isBlank()) {
            return Mono.just(RetrievalDebugResult.empty());
        }
        List<RetrievalDebugStage> stages = new ArrayList<>();
        RewriteSettings rewrite = resolved.rewrite();
        EffectiveRagConfig config = resolved.retrieval();
        return resolveInitialSearchQueryDebug(request, stages, rewrite)
                .flatMap(searchQuery -> executeRetrievalDebug(request, searchQuery, config, stages)
                        .flatMap(first -> {
                            if (!first.finalResults().isEmpty()) {
                                return Mono.just(first);
                            }
                            if (!request.rewrite()) {
                                return Mono.just(first);
                            }
                            return tryHydeFallbackDebug(request, config, stages, rewrite)
                                    .flatMap(hydeResult -> {
                                        if (!hydeResult.finalResults().isEmpty()) {
                                            return Mono.just(hydeResult);
                                        }
                                        return retryWithEmptyRecallDebug(request, config, stages, rewrite);
                                    });
                        }));
    }

    private Mono<String> resolveInitialSearchQuery(
            PipelineSearchRequest request, RetrievalTrace trace, RewriteSettings rewrite) {
        if (!request.rewrite() || !queryRewritePipeline.isRagEnabled(rewrite)) {
            return Mono.just(request.query());
        }
        return Mono.fromCallable(() -> {
                    QueryRewriteOutcome outcome = queryRewritePipeline.rewriteForRag(request.query(), rewrite);
                    if (trace != null) {
                        trace.addStage(toTraceStage(outcome));
                    }
                    return outcome.effectiveQuery();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> resolveInitialSearchQueryDebug(
            PipelineSearchRequest request, List<RetrievalDebugStage> stages, RewriteSettings rewrite) {
        if (!request.rewrite() || !queryRewritePipeline.isRagEnabled(rewrite)) {
            return Mono.just(request.query());
        }
        return Mono.fromCallable(() -> {
                    QueryRewriteOutcome outcome = queryRewritePipeline.rewriteForRag(request.query(), rewrite);
                    stages.add(toDebugRewriteStage(outcome));
                    return outcome.effectiveQuery();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<List<RetrievalService.DocFragment>> executeRetrieval(
            PipelineSearchRequest request, String searchQuery, RetrievalTrace trace, EffectiveRagConfig config) {
        if (trace != null) {
            trace.incrementSearchCount();
        }
        return retrievalService.search(
                searchQuery, request.topK(), request.strategy(), request.tenantId(), request.kbId(), config);
    }

    private Mono<RetrievalDebugResult> executeRetrievalDebug(
            PipelineSearchRequest request,
            String searchQuery,
            EffectiveRagConfig config,
            List<RetrievalDebugStage> stages) {
        return retrievalService.searchDebug(
                        searchQuery,
                        request.topK(),
                        request.strategy(),
                        request.tenantId(),
                        request.kbId(),
                        config)
                .map(result -> {
                    stages.addAll(result.stages());
                    return new RetrievalDebugResult(List.copyOf(stages), result.finalResults());
                });
    }

    private Mono<List<RetrievalService.DocFragment>> tryHydeFallback(
            PipelineSearchRequest request,
            RetrievalTrace trace,
            EffectiveRagConfig config,
            RewriteSettings rewrite) {
        if (!queryRewritePipeline.isHydeEnabled(rewrite)) {
            return retryWithEmptyRecallFragments(request, trace, List.of(), config, rewrite);
        }
        return Mono.fromCallable(() -> queryRewritePipeline.hydeForRag(request.query(), rewrite))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(hyde -> {
                    if (trace != null) {
                        trace.addStage(toTraceStage(hyde));
                    }
                    String hydeDoc = hyde.applied() ? hyde.rewrittenQuery() : null;
                    if (hydeDoc == null || hydeDoc.isBlank()) {
                        return retryWithEmptyRecallFragments(request, trace, List.of(), config, rewrite);
                    }
                    log.info("[KnowledgeRetrieval] HyDE fallback 检索");
                    return executeRetrieval(request, hydeDoc, trace, config)
                            .flatMap(hydeHits -> {
                                if (!hydeHits.isEmpty()) {
                                    return Mono.just(hydeHits);
                                }
                                return retryWithEmptyRecallFragments(request, trace, List.of(), config, rewrite);
                            });
                });
    }

    private Mono<RetrievalDebugResult> tryHydeFallbackDebug(
            PipelineSearchRequest request,
            EffectiveRagConfig config,
            List<RetrievalDebugStage> stages,
            RewriteSettings rewrite) {
        if (!queryRewritePipeline.isHydeEnabled(rewrite)) {
            return retryWithEmptyRecallDebug(request, config, stages, rewrite);
        }
        return Mono.fromCallable(() -> queryRewritePipeline.hydeForRag(request.query(), rewrite))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(hyde -> {
                    stages.add(toDebugRewriteStage(hyde));
                    String hydeDoc = hyde.applied() ? hyde.rewrittenQuery() : null;
                    if (hydeDoc == null || hydeDoc.isBlank()) {
                        return retryWithEmptyRecallDebug(request, config, stages, rewrite);
                    }
                    log.info("[KnowledgeRetrieval] HyDE fallback 检索");
                    return executeRetrievalDebug(request, hydeDoc, config, stages);
                });
    }

    private Mono<PipelineSearchResult> retryWithEmptyRecall(
            PipelineSearchRequest request,
            RetrievalTrace trace,
            EffectiveRagConfig config,
            RewriteSettings rewrite) {
        return retryWithEmptyRecallFragments(request, trace, List.of(), config, rewrite)
                .map(hits -> buildResult(request, resolveEffectiveFromTrace(trace, request.query()), hits, trace));
    }

    private Mono<RetrievalDebugResult> retryWithEmptyRecallDebug(
            PipelineSearchRequest request,
            EffectiveRagConfig config,
            List<RetrievalDebugStage> stages,
            RewriteSettings rewrite) {
        if (!queryRewritePipeline.isEmptyRecallEnabled(rewrite)) {
            return Mono.just(new RetrievalDebugResult(List.copyOf(stages), List.of()));
        }
        return Mono.fromCallable(() -> queryRewritePipeline.rewriteEmptyRecall(request.query(), rewrite))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    stages.add(toDebugRewriteStage(result.outcome()));
                    List<String> alternatives = result.alternatives();
                    if (alternatives.isEmpty()) {
                        return Mono.just(new RetrievalDebugResult(List.copyOf(stages), List.of()));
                    }
                    log.info("[KnowledgeRetrieval] empty-recall 二次检索: alts={}", alternatives);
                    return Flux.fromIterable(alternatives)
                            .concatMap(alt -> executeRetrievalDebug(request, alt, config, new ArrayList<>(stages)))
                            .collectList()
                            .map(results -> mergeDebugResults(results, request.topK(), stages));
                });
    }

    private Mono<List<RetrievalService.DocFragment>> retryWithEmptyRecallFragments(
            PipelineSearchRequest request,
            RetrievalTrace trace,
            List<RetrievalService.DocFragment> emptyFirst,
            EffectiveRagConfig config,
            RewriteSettings rewrite) {
        if (!queryRewritePipeline.isEmptyRecallEnabled(rewrite)) {
            return Mono.just(emptyFirst);
        }
        return Mono.fromCallable(() -> queryRewritePipeline.rewriteEmptyRecall(request.query(), rewrite))
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
                            .flatMap(alt -> executeRetrieval(request, alt, trace, config))
                            .collectList()
                            .map(batchLists -> mergeFragments(batchLists, request.topK()));
                });
    }

    private static RetrievalDebugResult mergeDebugResults(
            List<RetrievalDebugResult> results, int topK, List<RetrievalDebugStage> prefixStages) {
        List<List<RetrievalService.DocFragment>> batchLists = results.stream()
                .map(RetrievalDebugResult::finalResults)
                .toList();
        List<RetrievalService.DocFragment> merged = mergeFragments(batchLists, topK);
        List<RetrievalDebugStage> allStages = new ArrayList<>(prefixStages);
        for (RetrievalDebugResult result : results) {
            allStages.addAll(result.stages());
        }
        return new RetrievalDebugResult(allStages, merged);
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

    private RetrievalDebugStage toDebugRewriteStage(QueryRewriteOutcome outcome) {
        String label = rewriteProperties.timelineOrDefault().labelFor(outcome.scenario());
        return RetrievalDebugStage.rewrite(outcome, label);
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
