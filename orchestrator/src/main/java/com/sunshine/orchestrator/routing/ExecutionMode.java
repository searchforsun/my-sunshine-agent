package com.sunshine.orchestrator.routing;

/**
 * 顶层执行模式：fast / pro / workflow（读侧兼容旧 wire：auto/react/plan-workflow）。
 */
public enum ExecutionMode {
    FAST,
    PRO,
    WORKFLOW;

    public static ExecutionMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAST;
        }
        return switch (raw.toLowerCase().replace('_', '-')) {
            case "workflow", "pipeline" -> WORKFLOW;
            case "pro", "plan-workflow", "plan" -> PRO;
            case "fast", "react", "agent", "auto" -> FAST;
            default -> FAST;
        };
    }
}
