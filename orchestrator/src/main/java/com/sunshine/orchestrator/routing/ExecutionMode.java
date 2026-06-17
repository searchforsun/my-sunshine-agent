package com.sunshine.orchestrator.routing;

/**
 * 顶层执行模式：simple-llm / workflow / react
 */
public enum ExecutionMode {
    SIMPLE_LLM,
    WORKFLOW,
    REACT;

    public static ExecutionMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return REACT;
        }
        return switch (raw.toLowerCase().replace('_', '-')) {
            case "simple", "simple-llm", "direct" -> SIMPLE_LLM;
            case "workflow", "pipeline" -> WORKFLOW;
            default -> REACT;
        };
    }
}
