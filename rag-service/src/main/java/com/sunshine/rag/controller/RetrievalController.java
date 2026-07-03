package com.sunshine.rag.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.pipeline.KnowledgeRetrievalPipeline;
import com.sunshine.rag.pipeline.PipelineSearchRequest;
import com.sunshine.rag.service.RetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检索控制器 — 经 KnowledgeRetrievalPipeline 提供干净检索 API */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RetrievalController {
    private final KnowledgeRetrievalPipeline knowledgeRetrievalPipeline;
    private final EffectiveConfigResolver effectiveConfigResolver;

    @PostMapping("/search")
    public Mono<R<Map<String, Object>>> search(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            throw new BizException(RagErrorCode.QUERY_EMPTY);
        }
        String tid = resolveTenantId(body.get("tenantId"), tenantId);
        String kbId = body.get("kbId") != null ? String.valueOf(body.get("kbId")) : "default";
        int topK = body.containsKey("topK")
                ? ((Number) body.get("topK")).intValue()
                : effectiveConfigResolver.resolve(tid, kbId).defaultTopK();
        String strategy = (String) body.get("strategy");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = body.get("options") instanceof Map<?, ?> opt
                ? (Map<String, Object>) opt
                : null;
        Boolean rewrite = options != null && options.get("rewrite") instanceof Boolean b ? b : null;
        Boolean includeTrace = options != null && options.get("includeTrace") instanceof Boolean b ? b : null;
        PipelineSearchRequest request = PipelineSearchRequest.of(
                query, topK, tid, kbId, strategy, rewrite, includeTrace);
        return knowledgeRetrievalPipeline.search(request)
                .map(result -> R.ok(result.toResponseMap()));
    }

    private static Map<String, Object> toResultMap(RetrievalService.DocFragment fragment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docName", fragment.docName());
        item.put("content", fragment.content());
        item.put("score", fragment.score());
        return item;
    }

    private static String resolveTenantId(Object bodyTenant, String headerTenant) {
        if (bodyTenant != null && !String.valueOf(bodyTenant).isBlank()) {
            return String.valueOf(bodyTenant).strip();
        }
        if (headerTenant != null && !headerTenant.isBlank()) {
            return headerTenant.strip();
        }
        return "default";
    }
}
