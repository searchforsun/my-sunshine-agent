package com.sunshine.rag.admin.debug;

import com.sunshine.rag.model.RetrievalCandidate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调试瀑布候选片段 */
public record DebugCandidate(String docName, String content, float score, String source) {

    public static DebugCandidate from(RetrievalCandidate candidate) {
        return new DebugCandidate(
                candidate.docName(),
                candidate.content(),
                candidate.score(),
                candidate.source());
    }

    public static List<Map<String, Object>> toMaps(List<DebugCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream().map(DebugCandidate::toMap).toList();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("docName", docName);
        map.put("content", content);
        map.put("score", score);
        map.put("source", source);
        return map;
    }
}
