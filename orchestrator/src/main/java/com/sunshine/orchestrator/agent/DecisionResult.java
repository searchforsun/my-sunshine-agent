package com.sunshine.orchestrator.agent;

/** 用户决策结果（Registry Future 完成值） */
public record DecisionResult(
        String choice,
        String customInput,
        long decidedAt) {
}
