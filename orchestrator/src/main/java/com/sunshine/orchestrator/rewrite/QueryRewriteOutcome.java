package com.sunshine.orchestrator.rewrite;

import com.sunshine.orchestrator.processing.RewriteTimelineLabels;
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

    /** Timeline 展开区：场景时机说明 + 改写前后 query + 耗时；empty-recall 未生效时也展示 */
    public String timelineDetail() {
        if ("empty-recall".equals(scenario)) {
            return emptyRecallTimelineDetail();
        }
        if (!applied) {
            return null;
        }
        return rewriteTimelineDetail("hyde".equals(scenario) ? "参考文档" : "优化后", rewrittenQuery);
    }

    private String emptyRecallTimelineDetail() {
        String scenarioLabel = RewriteTimelineLabels.labelFor(scenario);
        if (applied) {
            return rewriteTimelineDetail("优化后", rewrittenQuery);
        }
        String body = "未能生成新的检索词"
                + "\n" + formatLatency(latencyMs);
        if (StringUtils.hasText(scenarioLabel)) {
            return scenarioLabel + "\n" + body;
        }
        return body;
    }

    private String rewriteTimelineDetail(String targetLabel, String targetText) {
        String body = "原问题：" + clip(originalQuery)
                + "\n" + targetLabel + "：" + clip(targetText)
                + "\n" + formatLatency(latencyMs);
        String scenarioLabel = RewriteTimelineLabels.labelFor(scenario);
        if (StringUtils.hasText(scenarioLabel)) {
            return scenarioLabel + "\n" + body;
        }
        return body;
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
