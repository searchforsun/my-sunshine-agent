package com.sunshine.orchestrator.plan;

/** 节点单次执行 attempt — 写入 execution_trace */
public record PlanNodeAttempt(
        int attemptNo,
        String status,
        String errorClass,
        String summary,
        Long startedAt,
        Long endedAt
) {
}
