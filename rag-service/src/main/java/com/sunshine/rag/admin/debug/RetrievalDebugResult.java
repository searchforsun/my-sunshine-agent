package com.sunshine.rag.admin.debug;

import com.sunshine.rag.service.RetrievalService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检索调试完整结果 */
public record RetrievalDebugResult(
        List<RetrievalDebugStage> stages,
        List<RetrievalService.DocFragment> finalResults) {

    public static RetrievalDebugResult empty() {
        return new RetrievalDebugResult(List.of(), List.of());
    }

    public RetrievalDebugResult withPrependedStages(List<RetrievalDebugStage> prefix) {
        List<RetrievalDebugStage> merged = new ArrayList<>(prefix);
        merged.addAll(stages);
        return new RetrievalDebugResult(merged, finalResults);
    }

    public Map<String, Object> toResponseMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stages", stages.stream().map(RetrievalDebugStage::toMap).toList());
        map.put("final", finalResults.stream().map(RetrievalDebugResult::fragmentToMap).toList());
        return map;
    }

    private static Map<String, Object> fragmentToMap(RetrievalService.DocFragment fragment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docName", fragment.docName());
        item.put("content", fragment.content());
        item.put("score", fragment.score());
        return item;
    }
}
