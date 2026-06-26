package com.sunshine.orchestrator.plan;

/** execution_plan.status 状态机 */
public enum ExecutionPlanStatus {
    DRAFT,
    VALIDATED,
    RUNNING,
    PAUSED,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    FAILED,
    REJECTED,
    DEGRADED_REACT;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static ExecutionPlanStatus fromDb(String raw) {
        if (raw == null || raw.isBlank()) {
            return DRAFT;
        }
        return ExecutionPlanStatus.valueOf(raw.strip().toUpperCase());
    }
}
