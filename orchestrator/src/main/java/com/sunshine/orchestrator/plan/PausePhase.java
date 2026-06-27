package com.sunshine.orchestrator.plan;

/** Plan 暂停阶段：规划期 vs DAG 执行期 */
public enum PausePhase {
    PLANNING,
    EXECUTING;

    public static PausePhase fromDb(String value) {
        if (value == null || value.isBlank()) {
            return EXECUTING;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return EXECUTING;
        }
    }
}
