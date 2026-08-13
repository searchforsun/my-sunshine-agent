package com.sunshine.orchestrator.routing;

/**
 * Chat 执行偏好（协议 wire：fast|pro|workflow）。
 * 读侧兼容旧值 auto/react/plan-workflow；无「路由自判」AUTO。
 */
public enum ExecutionPreference {
    FAST,
    PRO,
    WORKFLOW;

    public static ExecutionPreference from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAST;
        }
        return switch (raw.strip().toLowerCase().replace('_', '-')) {
            case "workflow", "pipeline" -> WORKFLOW;
            case "pro", "plan-workflow", "plan" -> PRO;
            case "fast", "react", "agent", "auto" -> FAST;
            default -> FAST;
        };
    }

    /** L0 @skill / hint 绑定是否允许 */
    public boolean allowsSkillBinding() {
        return this == FAST || this == PRO;
    }

    /** 三模式均钉死分发，无 auto 自判 */
    public boolean isForced() {
        return true;
    }

    /** API / DB 存储值 */
    public String wireValue() {
        return switch (this) {
            case FAST -> "fast";
            case PRO -> "pro";
            case WORKFLOW -> "workflow";
        };
    }
}
