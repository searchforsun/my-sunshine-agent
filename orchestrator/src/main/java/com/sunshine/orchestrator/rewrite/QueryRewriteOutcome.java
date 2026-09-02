package com.sunshine.orchestrator.rewrite;

import org.springframework.util.StringUtils;

/**
 * 单次 Query 改写结果 — 供 Timeline detail 与审计 payload 复用。
 * scenarioLabel 由 rag-service trace 透传；intent 改写已退役。
 */
public record QueryRewriteOutcome(
        String scenario,
        String originalQuery,
        String rewrittenQuery,
        boolean applied,
        long latencyMs,
        String scenarioLabel) {

    public QueryRewriteOutcome(String scenario, String originalQuery, String rewrittenQuery, boolean applied, long latencyMs) {
        this(scenario, originalQuery, rewrittenQuery, applied, latencyMs, null);
    }

    public static QueryRewriteOutcome skipped(String scenario, String query, long latencyMs) {
        return skipped(scenario, query, latencyMs, null);
    }

    public static QueryRewriteOutcome skipped(String scenario, String query, long latencyMs, String scenarioLabel) {
        String q = query != null ? query.strip() : "";
        return new QueryRewriteOutcome(scenario, q, q, false, latencyMs, scenarioLabel);
    }

    public static QueryRewriteOutcome of(String scenario, String original, String rewritten, long latencyMs) {
        return of(scenario, original, rewritten, latencyMs, null);
    }

    public static QueryRewriteOutcome of(String scenario, String original, String rewritten, long latencyMs, String scenarioLabel) {
        String from = original != null ? original.strip() : "";
        String to = StringUtils.hasText(rewritten) ? rewritten.strip() : from;
        boolean applied = StringUtils.hasText(to) && !to.equals(from);
        return new QueryRewriteOutcome(scenario, from, applied ? to : from, applied, latencyMs, scenarioLabel);
    }

    public static QueryRewriteOutcome emptyRecall(String original, java.util.List<String> alternatives, long latencyMs) {
        return emptyRecall(original, alternatives, latencyMs, null);
    }

    public static QueryRewriteOutcome emptyRecall(
            String original, java.util.List<String> alternatives, long latencyMs, String scenarioLabel) {
        String from = original != null ? original.strip() : "";
        if (alternatives == null || alternatives.isEmpty()) {
            return skipped(QueryRewriteScenario.EMPTY_RECALL.id(), from, latencyMs, scenarioLabel);
        }
        String to = String.join("；", alternatives);
        return new QueryRewriteOutcome(QueryRewriteScenario.EMPTY_RECALL.id(), from, to, true, latencyMs, scenarioLabel);
    }

    public String effectiveQuery() {
        return applied ? rewrittenQuery : originalQuery;
    }

    /** Timeline 展开区：场景时机说明 + 改写前后 query + 耗时；empty-recall 未生效时也展示 */
    public String timelineDetail() {
        if (QueryRewriteScenario.EMPTY_RECALL.matches(scenario)) {
            return emptyRecallTimelineDetail();
        }
        if (!applied) {
            return null;
        }
        return rewriteTimelineDetail(QueryRewriteScenario.HYDE.matches(scenario) ? "参考文档" : "优化后", rewrittenQuery);
    }

    private String emptyRecallTimelineDetail() {
        String label = resolveScenarioLabel();
        if (applied) {
            return rewriteTimelineDetail("优化后", rewrittenQuery);
        }
        String body = "未能生成新的检索词"
                + "\n" + formatLatency(latencyMs);
        if (StringUtils.hasText(label)) {
            return label + "\n" + body;
        }
        return body;
    }

    private String rewriteTimelineDetail(String targetLabel, String targetText) {
        String body = "原问题：" + clip(originalQuery)
                + "\n" + targetLabel + "：" + clip(targetText)
                + "\n" + formatLatency(latencyMs);
        String label = resolveScenarioLabel();
        if (StringUtils.hasText(label)) {
            return label + "\n" + body;
        }
        return body;
    }

    /** trace 透传优先；无 label 时返回空 */
    public String resolveScenarioLabel() {
        if (StringUtils.hasText(scenarioLabel)) {
            return scenarioLabel.strip();
        }
        return "";
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String s = text.strip();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static String formatLatency(long latencyMs) {
        if (latencyMs < 1) {
            return "<1ms";
        }
        if (latencyMs < 1000) {
            return latencyMs + "ms";
        }
        return String.format("%.1fs", latencyMs / 1000.0);
    }
}
