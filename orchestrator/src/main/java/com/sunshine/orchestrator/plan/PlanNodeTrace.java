package com.sunshine.orchestrator.plan;

import java.util.List;

/** 节点执行摘要 — 写入 execution_trace JSON 数组 */
public record PlanNodeTrace(
        String nodeId,
        String type,
        String status,
        String summary,
        String detail,
        Long startedAt,
        Long endedAt,
        Integer attemptCount,
        String onFailure,
        List<PlanNodeAttempt> attempts
) {
    public PlanNodeTrace(
            String nodeId,
            String type,
            String status,
            String summary,
            String detail,
            Long startedAt,
            Long endedAt) {
        this(nodeId, type, status, summary, detail, startedAt, endedAt, null, null, null);
    }
}
