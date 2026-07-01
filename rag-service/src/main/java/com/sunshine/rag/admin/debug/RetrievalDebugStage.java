package com.sunshine.rag.admin.debug;

import com.sunshine.rag.model.RetrievalCandidate;
import com.sunshine.rag.pipeline.QueryRewriteOutcome;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 检索调试单阶段 */
public record RetrievalDebugStage(
        String name,
        Boolean applied,
        String from,
        String to,
        List<DebugCandidate> candidates,
        List<DebugCandidate> dropped,
        long latencyMs,
        String scenarioLabel) {

    public static RetrievalDebugStage rewrite(QueryRewriteOutcome outcome, String scenarioLabel) {
        return new RetrievalDebugStage(
                outcome.scenario(),
                outcome.applied(),
                outcome.originalQuery(),
                outcome.applied() ? outcome.rewrittenQuery() : null,
                null,
                null,
                outcome.latencyMs(),
                scenarioLabel);
    }

    public static RetrievalDebugStage retrieval(
            String name, List<RetrievalCandidate> candidates, List<RetrievalCandidate> dropped, long latencyMs) {
        return new RetrievalDebugStage(
                name,
                null,
                null,
                null,
                toDebugList(candidates),
                toDebugList(dropped),
                latencyMs,
                null);
    }

    private static List<DebugCandidate> toDebugList(List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream().map(DebugCandidate::from).toList();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("latencyMs", latencyMs);
        if (applied != null) {
            map.put("applied", applied);
        }
        if (from != null) {
            map.put("from", from);
        }
        if (to != null) {
            map.put("to", to);
        }
        if (scenarioLabel != null && !scenarioLabel.isBlank()) {
            map.put("scenarioLabel", scenarioLabel);
        }
        if (candidates != null) {
            map.put("candidates", DebugCandidate.toMaps(candidates));
        }
        if (dropped != null && !dropped.isEmpty()) {
            map.put("dropped", DebugCandidate.toMaps(dropped));
        }
        return map;
    }
}
