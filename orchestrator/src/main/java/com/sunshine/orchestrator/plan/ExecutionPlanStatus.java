package com.sunshine.orchestrator.plan;

/** execution_plan.status 状态机 */
public enum ExecutionPlanStatus {
    DRAFT,
    VALIDATED,
    RUNNING,
    COMPLETED,
    FAILED,
    REJECTED;

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
