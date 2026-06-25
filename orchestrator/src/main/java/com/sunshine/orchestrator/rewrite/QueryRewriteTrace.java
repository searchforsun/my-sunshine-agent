package com.sunshine.orchestrator.rewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 按 assistant messageId 收集一次对话内的 Query 改写事件（跨线程安全）。
 */
public final class QueryRewriteTrace {

    private static final Map<String, List<QueryRewriteOutcome>> TRACES = new ConcurrentHashMap<>();
    /** RAG 步骤展开区仅展示检索相关改写，意图改写已在 intent 步展示 */
    private static final Set<String> RAG_SCENARIOS = Set.of("rag", "hyde", "empty-recall");

    private QueryRewriteTrace() {
    }

    public static void bind(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            TRACES.put(messageId, Collections.synchronizedList(new ArrayList<>()));
        }
    }

    public static void record(String messageId, QueryRewriteOutcome outcome) {
        if (messageId == null || messageId.isBlank() || outcome == null) {
            return;
        }
        List<QueryRewriteOutcome> list = TRACES.get(messageId);
        if (list != null) {
            list.add(outcome);
        }
    }

    public static Optional<QueryRewriteOutcome> latest(String messageId, String scenario) {
        if (messageId == null || scenario == null) {
            return Optional.empty();
        }
        List<QueryRewriteOutcome> list = TRACES.get(messageId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        QueryRewriteOutcome found = null;
        synchronized (list) {
            for (QueryRewriteOutcome outcome : list) {
                if (scenario.equals(outcome.scenario())) {
                    found = outcome;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    public static List<QueryRewriteOutcome> all(String messageId) {
        List<QueryRewriteOutcome> list = messageId == null ? null : TRACES.get(messageId);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
        }
    }

    /** 当前 message 已记录的改写条数（用于 RAG 步骤按次切片） */
    public static int size(String messageId) {
        List<QueryRewriteOutcome> list = messageId == null ? null : TRACES.get(messageId);
        if (list == null) {
            return 0;
        }
        synchronized (list) {
            return list.size();
        }
    }

    public static Optional<QueryRewriteOutcome> intentOutcome(String messageId) {
        return latest(messageId, "intent");
    }

    public static Optional<QueryRewriteOutcome> plannerOutcome(String messageId) {
        return latest(messageId, "planner");
    }

    public static String combinedTimelineDetail(String messageId) {
        return joinTimelineDetails(all(messageId));
    }

    /** RAG / workflow node-rag 展开区：不含 intent 改写（避免与意图步重复） */
    public static String combinedRagTimelineDetail(String messageId) {
        return combinedRagTimelineDetailSince(messageId, 0);
    }

    /** 仅拼接自 {@code fromIndex} 起新增的 RAG 相关改写（多次 search_knowledge 互不叠加） */
    public static String combinedRagTimelineDetailSince(String messageId, int fromIndex) {
        List<QueryRewriteOutcome> all = all(messageId);
        if (fromIndex < 0) {
            fromIndex = 0;
        }
        if (fromIndex >= all.size()) {
            return joinTimelineDetails(List.of());
        }
        List<QueryRewriteOutcome> ragOnly = all.subList(fromIndex, all.size()).stream()
                .filter(o -> RAG_SCENARIOS.contains(o.scenario()))
                .collect(Collectors.toList());
        return joinTimelineDetails(ragOnly);
    }

    /** 自 {@code fromIndex} 起最后一次指定场景的改写 */
    public static Optional<QueryRewriteOutcome> latestSince(String messageId, String scenario, int fromIndex) {
        if (messageId == null || scenario == null) {
            return Optional.empty();
        }
        List<QueryRewriteOutcome> list = TRACES.get(messageId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        QueryRewriteOutcome found = null;
        synchronized (list) {
            int start = Math.max(0, fromIndex);
            for (int i = start; i < list.size(); i++) {
                QueryRewriteOutcome outcome = list.get(i);
                if (scenario.equals(outcome.scenario())) {
                    found = outcome;
                }
            }
        }
        return Optional.ofNullable(found);
    }

    private static String joinTimelineDetails(List<QueryRewriteOutcome> outcomes) {
        List<String> parts = outcomes.stream()
                .map(QueryRewriteOutcome::timelineDetail)
                .filter(d -> d != null && !d.isBlank())
                .collect(Collectors.toList());
        if (parts.isEmpty()) {
            return null;
        }
        return String.join("\n\n", parts);
    }

    public static AuditRewriteSummary auditSummary(String messageId) {
        List<QueryRewriteOutcome> outcomes = all(messageId);
        boolean applied = outcomes.stream().anyMatch(QueryRewriteOutcome::applied);
        long latencyMs = outcomes.stream().mapToLong(QueryRewriteOutcome::latencyMs).sum();
        return new AuditRewriteSummary(applied, latencyMs, outcomes);
    }

    public static void clear(String messageId) {
        if (messageId != null) {
            TRACES.remove(messageId);
        }
    }

    public record AuditRewriteSummary(
            boolean rewriteApplied,
            long rewriteLatencyMs,
            List<QueryRewriteOutcome> outcomes) {
    }
}
