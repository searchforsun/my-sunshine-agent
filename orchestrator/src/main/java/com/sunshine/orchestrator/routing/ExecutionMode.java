package com.sunshine.orchestrator.routing;

/**
 * 顶层执行模式：simple-llm / workflow / react / plan-workflow
 */
public enum ExecutionMode {
    SIMPLE_LLM,
    WORKFLOW,
    REACT,
    PLAN_WORKFLOW;

    public static ExecutionMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return REACT;
        }
        return switch (raw.toLowerCase().replace('_', '-')) {
            case "simple", "simple-llm", "direct" -> SIMPLE_LLM;
            case "workflow", "pipeline" -> WORKFLOW;
            case "plan-workflow", "plan_workflow", "plan" -> PLAN_WORKFLOW;
            default -> REACT;
        };
    }
}
