package com.sunshine.orchestrator.plan.harness;

import java.util.List;

/** H1 可调度粗单元；status: pending|in_progress|done|fail|obsolete */
public record TaskItem(
        String taskId,
        String label,
        String status,
        List<String> dependsOn,
        String constraints,
        String expectedOutput,
        String successCriteria) {
}
