package com.sunshine.orchestrator.routing;

/** 用户指定的 Chat 执行偏好（auto = 走 Policy Chain） */
public enum ExecutionPreference {
    AUTO,
    SIMPLE_LLM,
    REACT,
    WORKFLOW,
    PLAN_WORKFLOW;

    public static ExecutionPreference from(String raw) {
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.strip())) {
            return AUTO;
        }
        return switch (raw.strip().toLowerCase().replace('_', '-')) {
            case "simple", "simple-llm", "direct" -> SIMPLE_LLM;
            case "react", "agent" -> REACT;
            case "workflow", "pipeline" -> WORKFLOW;
            case "plan-workflow", "plan", "plan_workflow" -> PLAN_WORKFLOW;
            default -> AUTO;
        };
    }

    /** L0 @skill / hint 绑定是否允许 */
    public boolean allowsSkillBinding() {
        return this == AUTO || this == REACT || this == PLAN_WORKFLOW;
    }

    public boolean isForced() {
        return this != AUTO;
    }

    /** API / DB 存储值 */
    public String wireValue() {
        return switch (this) {
            case AUTO -> "auto";
            case SIMPLE_LLM -> "simple-llm";
            case REACT -> "react";
            case WORKFLOW -> "workflow";
            case PLAN_WORKFLOW -> "plan-workflow";
        };
    }
}
