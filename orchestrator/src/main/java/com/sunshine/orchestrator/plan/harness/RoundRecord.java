package com.sunshine.orchestrator.plan.harness;

import java.util.List;

public record RoundRecord(
        int roundIndex,
        TaskItem task,
        List<NodeResult> nodeResults,
        double roundGoalCompletion,
        String assessReason) {
}
