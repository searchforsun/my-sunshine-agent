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
    /** RAG 步骤 id → 本次 search_knowledge RPC 在 trace 中的 [start, end) 切片 */
    private static final Map<String, RagSpan> RAG_SPANS_BY_STEP = new ConcurrentHashMap<>();
    /** 并行 RAG：按 ragStepId 隔离改写（避免全局 trace 索引竞态） */
    private static final Map<String, Map<String, List<QueryRewriteOutcome>>> OUTCOMES_BY_RAG_STEP =
            new ConcurrentHashMap<>();

    public record RagSpan(int startIndex, int endIndex) {
    }

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

    /** 并行 workflow / ReAct 工具步：改写按 ragStepId 独立落盘 */
    public static void recordForRagStep(String messageId, String ragStepId, QueryRewriteOutcome outcome) {
        if (messageId == null || messageId.isBlank() || ragStepId == null || ragStepId.isBlank() || outcome == null) {
            return;
        }
        OUTCOMES_BY_RAG_STEP
                .computeIfAbsent(messageId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ragStepId.strip(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(outcome);
    }

    public static List<QueryRewriteOutcome> outcomesForStep(String messageId, String ragStepId) {
        if (messageId == null || ragStepId == null || ragStepId.isBlank()) {
            return List.of();
        }
        Map<String, List<QueryRewriteOutcome>> byStep = OUTCOMES_BY_RAG_STEP.get(messageId);
        if (byStep == null) {
            return List.of();
        }
        List<QueryRewriteOutcome> list = byStep.get(ragStepId.strip());
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        synchronized (list) {
            return List.copyOf(list);
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
        return latest(messageId, QueryRewriteScenario.INTENT.id());
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
        return combinedRagTimelineDetailBetween(messageId, fromIndex, size(messageId));
    }

    /** 按 ragStepId 取改写详情（并行安全；无 per-step 记录时回退 span 切片） */
    public static String combinedRagTimelineDetailForStep(String messageId, String ragStepId) {
        if (messageId == null || ragStepId == null || ragStepId.isBlank()) {
            return null;
        }
        List<QueryRewriteOutcome> perStep = outcomesForStep(messageId, ragStepId);
        if (!perStep.isEmpty()) {
            return joinTimelineDetails(perStep);
        }
        return ragSpan(ragStepId)
                .map(span -> combinedRagTimelineDetailBetween(messageId, span.startIndex(), span.endIndex()))
                .orElse(null);
    }

    /** 拼接 trace 中 {@code [fromIndex, toIndex)} 区间的 RAG 相关改写 */
    public static String combinedRagTimelineDetailBetween(String messageId, int fromIndex, int toIndex) {
        List<QueryRewriteOutcome> all = all(messageId);
        if (fromIndex < 0) {
            fromIndex = 0;
        }
        if (toIndex > all.size()) {
            toIndex = all.size();
        }
        if (fromIndex >= toIndex) {
            return joinTimelineDetails(List.of());
        }
        List<QueryRewriteOutcome> ragOnly = all.subList(fromIndex, toIndex).stream()
                .filter(o -> QueryRewriteScenario.isRagRelated(o.scenario()))
                .collect(Collectors.toList());
        return joinTimelineDetails(ragOnly);
    }

    /** search_knowledge RPC 入口 — 记录 trace 起点；清空该步旧改写（loop 同节点多轮不叠加） */
    public static void beginRagSpan(String stepId, String messageId) {
        if (stepId == null || stepId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        String key = stepId.strip();
        clearRagStepOutcomes(messageId, key);
        int start = size(messageId);
        RAG_SPANS_BY_STEP.put(key, new RagSpan(start, start));
    }

    /** 清空单步 per-step 改写列表（每次 RAG 检索入口调用） */
    public static void clearRagStepOutcomes(String messageId, String ragStepId) {
        if (messageId == null || ragStepId == null || ragStepId.isBlank()) {
            return;
        }
        Map<String, List<QueryRewriteOutcome>> byStep = OUTCOMES_BY_RAG_STEP.get(messageId);
        if (byStep != null) {
            byStep.remove(ragStepId.strip());
        }
    }

    /** search_knowledge RPC 出口 — 闭合 trace 终点 */
    public static void endRagSpan(String stepId, String messageId) {
        if (stepId == null || stepId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        String key = stepId.strip();
        RagSpan span = RAG_SPANS_BY_STEP.get(key);
        if (span == null) {
            return;
        }
        RAG_SPANS_BY_STEP.put(key, new RagSpan(span.startIndex(), size(messageId)));
    }

    public static java.util.Optional<RagSpan> ragSpan(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(RAG_SPANS_BY_STEP.get(stepId.strip()));
    }

    /** 自 {@code fromIndex} 起最后一次指定场景的改写 */
    public static Optional<QueryRewriteOutcome> latestSince(String messageId, String scenario, int fromIndex) {
        return latestBetween(messageId, scenario, fromIndex, size(messageId));
    }

    /** {@code [fromIndex, toIndex)} 区间内最后一次指定场景的改写 */
    public static Optional<QueryRewriteOutcome> latestBetween(
            String messageId, String scenario, int fromIndex, int toIndex) {
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
            int end = Math.min(toIndex, list.size());
            for (int i = start; i < end; i++) {
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
            OUTCOMES_BY_RAG_STEP.remove(messageId);
        }
    }

    /** 移除单步 RAG span（completeAt 后） */
    public static void clearRagSpan(String stepId) {
        if (stepId != null && !stepId.isBlank()) {
            RAG_SPANS_BY_STEP.remove(stepId.strip());
        }
    }

    public record AuditRewriteSummary(
            boolean rewriteApplied,
            long rewriteLatencyMs,
            List<QueryRewriteOutcome> outcomes) {
    }
}
