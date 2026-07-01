package com.sunshine.rag.pipeline;

import com.sunshine.rag.service.RetrievalService;

import java.util.List;
import java.util.Map;

/** Pipeline 检索结果 */
public record PipelineSearchResult(
        String query,
        String effectiveQuery,
        List<RetrievalService.DocFragment> results,
        RetrievalTrace trace) {

    public Map<String, Object> toResponseMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("query", query);
        map.put("effectiveQuery", effectiveQuery);
        map.put("results", results.stream().map(PipelineSearchResult::fragmentToMap).toList());
        if (trace != null) {
            map.put("trace", trace.toMap());
        }
        return map;
    }

    private static Map<String, Object> fragmentToMap(RetrievalService.DocFragment f) {
        Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("docName", f.docName());
        item.put("content", f.content());
        item.put("score", f.score());
        return item;
    }
}
