package com.sunshine.orchestrator.rewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 按 assistant messageId 收集一次对话内的 Query 改写事件（跨线程安全）。
 */
public final class QueryRewriteTrace {

    private static final Map<String, List<QueryRewriteOutcome>> TRACES = new ConcurrentHashMap<>();

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

    public static Optional<QueryRewriteOutcome> intentOutcome(String messageId) {
        return latest(messageId, "intent");
    }

    public static String combinedTimelineDetail(String messageId) {
        List<String> parts = all(messageId).stream()
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
