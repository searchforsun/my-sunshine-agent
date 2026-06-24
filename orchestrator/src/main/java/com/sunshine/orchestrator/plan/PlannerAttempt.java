package com.sunshine.orchestrator.plan;

/** 单次 Planner / Replan 调用记录 */
public record PlannerAttempt(
        int attemptNo,
        String phase,
        String status,
        String error,
        Long startedAt,
        Long endedAt
) {
}
