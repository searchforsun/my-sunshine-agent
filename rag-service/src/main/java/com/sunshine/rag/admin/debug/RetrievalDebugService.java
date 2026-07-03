package com.sunshine.rag.admin.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.config.ConfigResolveMode;
import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.admin.config.ResolvedKbConfig;
import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.pipeline.KnowledgeRetrievalPipeline;
import com.sunshine.rag.pipeline.PipelineSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class RetrievalDebugService {

    private final KnowledgeRetrievalPipeline knowledgeRetrievalPipeline;
    private final EffectiveConfigResolver effectiveConfigResolver;
    private final RagSearchProperties searchProperties;
    private final ObjectMapper objectMapper;

    public Mono<RetrievalDebugResult> debugSearch(String tenantId, Map<String, Object> body) {
        String query = body.get("query") != null ? String.valueOf(body.get("query")).strip() : "";
        if (query.isBlank()) {
            throw new BizException(RagErrorCode.QUERY_EMPTY);
        }
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        String kbId = body.get("kbId") != null ? String.valueOf(body.get("kbId")).strip() : "default";
        int topK = body.containsKey("topK")
                ? ((Number) body.get("topK")).intValue()
                : searchProperties.getDefaultTopK();
        String strategy = body.get("strategy") != null ? String.valueOf(body.get("strategy")) : null;
        boolean includeRewrite = body.get("includeRewrite") instanceof Boolean b ? b : true;
        ConfigResolveMode mode = ConfigResolveMode.parse(
                body.get("configMode") != null ? String.valueOf(body.get("configMode")) : "published");
        Long versionId = body.get("configVersionId") instanceof Number number ? number.longValue() : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> overrides = body.get("overrides") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : null;
        ResolvedKbConfig resolved = effectiveConfigResolver.resolve(tid, kbId, mode, versionId);
        if (overrides != null && !overrides.isEmpty()) {
            EffectiveRagConfig patched = applyOverrides(resolved.retrieval(), overrides);
            resolved = new ResolvedKbConfig(
                    patched, resolved.rewrite(), resolved.defaultTopK(), resolved.chunkMaxSize());
        }
        PipelineSearchRequest request = PipelineSearchRequest.of(
                query, topK, tid, kbId, strategy, includeRewrite, true);
        return knowledgeRetrievalPipeline.debugSearch(request, resolved);
    }

    private EffectiveRagConfig applyOverrides(EffectiveRagConfig base, Map<String, Object> overrides) {
        try {
            EffectiveRagConfig patch = objectMapper.convertValue(overrides, EffectiveRagConfig.class);
            return base.merge(patch);
        } catch (IllegalArgumentException e) {
            throw new BizException(RagErrorCode.QUERY_EMPTY);
        }
    }
}
