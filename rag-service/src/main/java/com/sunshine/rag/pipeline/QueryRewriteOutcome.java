package com.sunshine.rag.pipeline;

import org.springframework.util.StringUtils;

import java.util.List;

/** 单次 Query 改写结果 */
public record QueryRewriteOutcome(
        String scenario,
        String originalQuery,
        String rewrittenQuery,
        boolean applied,
        long latencyMs) {

    public static QueryRewriteOutcome skipped(String scenario, String query, long latencyMs) {
        String q = query != null ? query.strip() : "";
        return new QueryRewriteOutcome(scenario, q, q, false, latencyMs);
    }

    public static QueryRewriteOutcome of(String scenario, String original, String rewritten, long latencyMs) {
        String from = original != null ? original.strip() : "";
        String to = StringUtils.hasText(rewritten) ? rewritten.strip() : from;
        boolean applied = StringUtils.hasText(to) && !to.equals(from);
        return new QueryRewriteOutcome(scenario, from, applied ? to : from, applied, latencyMs);
    }

    public static QueryRewriteOutcome emptyRecall(String original, List<String> alternatives, long latencyMs) {
        String from = original != null ? original.strip() : "";
        if (alternatives == null || alternatives.isEmpty()) {
            return skipped("empty-recall", from, latencyMs);
        }
        return new QueryRewriteOutcome("empty-recall", from, String.join("；", alternatives), true, latencyMs);
    }

    public String effectiveQuery() {
        return applied ? rewrittenQuery : originalQuery;
    }

    /** 供 orchestrator Timeline 透传；scenarioLabel 来自 rag.rewrite.timeline */
    public RetrievalStage toStage(String scenarioLabel) {
        return new RetrievalStage(
                scenario,
                applied,
                originalQuery,
                applied ? rewrittenQuery : null,
                null,
                latencyMs,
                scenarioLabel);
    }
}
