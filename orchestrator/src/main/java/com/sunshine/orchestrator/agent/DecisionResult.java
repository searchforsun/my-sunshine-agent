package com.sunshine.orchestrator.agent;

import java.util.List;

/** 用户决策结果（Registry Future 完成值） */
public record DecisionResult(
        String outcome,
        String title,
        List<DecisionAnswer> answers,
        long decidedAt) {
}
