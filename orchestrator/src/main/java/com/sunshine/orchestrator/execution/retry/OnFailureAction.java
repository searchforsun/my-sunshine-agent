package com.sunshine.orchestrator.execution.retry;

/** 节点重试耗尽后的降级策略 */
public enum OnFailureAction {
    CONTINUE,
    FAIL_FAST,
    SKIP,
    FALLBACK_REACT;

    public static OnFailureAction fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return CONTINUE;
        }
        try {
            return OnFailureAction.valueOf(raw.strip().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return CONTINUE;
        }
    }
}
