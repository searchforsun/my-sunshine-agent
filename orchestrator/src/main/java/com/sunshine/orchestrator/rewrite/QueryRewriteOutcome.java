package com.sunshine.orchestrator.rewrite;

import org.springframework.util.StringUtils;

/**
 * 单次 Query 改写结果 — 供 Timeline detail 与审计 payload 复用。
 */
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

    public static QueryRewriteOutcome emptyRecall(String original, java.util.List<String> alternatives, long latencyMs) {
        String from = original != null ? original.strip() : "";
        if (alternatives == null || alternatives.isEmpty()) {
            return skipped("empty-recall", from, latencyMs);
        }
        String to = String.join("；", alternatives);
        return new QueryRewriteOutcome("empty-recall", from, to, true, latencyMs);
    }

    public String effectiveQuery() {
        return applied ? rewrittenQuery : originalQuery;
    }

    /** Timeline 展开区：改写前后 query + 耗时 */
    public String timelineDetail() {
        if (!applied) {
            return null;
        }
        String targetLabel = "hyde".equals(scenario) ? "HyDE" : "改写后";
        return "改写前：" + clip(originalQuery)
                + "\n" + targetLabel + "：" + clip(rewrittenQuery)
                + "\n耗时：" + latencyMs + "ms";
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String s = text.strip();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
