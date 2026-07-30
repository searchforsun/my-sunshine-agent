package com.sunshine.orchestrator.routing;

/**
 * 顶层执行模式：workflow / react / plan-workflow
 */
public enum ExecutionMode {
    WORKFLOW,
    REACT,
    PLAN_WORKFLOW;

    public static ExecutionMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return REACT;
        }
        return switch (raw.toLowerCase().replace('_', '-')) {
            case "workflow", "pipeline" -> WORKFLOW;
            case "plan-workflow", "plan_workflow", "plan" -> PLAN_WORKFLOW;
            default -> REACT; // includes historical simple-llm / simple / direct
        };
    }
}
