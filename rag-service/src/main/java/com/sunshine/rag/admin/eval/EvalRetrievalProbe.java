package com.sunshine.rag.admin.eval;

import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.pipeline.KnowledgeRetrievalPipeline;
import com.sunshine.rag.pipeline.PipelineSearchRequest;
import com.sunshine.rag.pipeline.PipelineSearchResult;
import com.sunshine.rag.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Golden set 评测检索探针 */
@Component
@RequiredArgsConstructor
public class EvalRetrievalProbe {

    @Lazy
    private final KnowledgeRetrievalPipeline pipeline;

    List<RetrievalService.DocFragment> searchHits(
            String query,
            int topK,
            String tenantId,
            String kbId,
            String strategy,
            boolean rewrite,
            EffectiveRagConfig config) {
        PipelineSearchRequest request = PipelineSearchRequest.of(
                query, topK, tenantId, kbId, strategy, rewrite, false);
        PipelineSearchResult result = pipeline.searchWithConfig(request, config).block();
        return result != null ? result.results() : List.of();
    }

    static Set<String> resolveRelevantNames(
            GoldenSetLoader.GoldenQuery query, Map<String, String> id2name) {
        Set<String> relevant = new LinkedHashSet<>();
        for (String docId : query.relevantDocIds()) {
            String name = id2name.get(docId);
            if (name != null) {
                relevant.add(name);
            }
        }
        return relevant;
    }
}
