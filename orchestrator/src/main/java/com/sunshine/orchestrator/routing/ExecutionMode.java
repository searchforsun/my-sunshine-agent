package com.sunshine.orchestrator.routing;

/**
 * 顶层执行模式（协议 wire 唯一取值：fast / pro / workflow）。
 * 无「路由自判」AUTO；未知值视为非法。
 */
public enum ExecutionMode {
    FAST,
    PRO,
    WORKFLOW;

    public static ExecutionMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAST;
        }
        return switch (raw.strip().toLowerCase().replace('_', '-')) {
            case "fast" -> FAST;
            case "pro" -> PRO;
            case "workflow" -> WORKFLOW;
            default -> throw new IllegalArgumentException("unknown execution mode: " + raw);
        };
    }

    /** L0 /skill / hint 绑定是否允许 */
    public boolean allowsSkillBinding() {
        return this == FAST || this == PRO;
    }

    /** API / DB 存储值 */
    public String wireValue() {
        return switch (this) {
            case FAST -> "fast";
            case PRO -> "pro";
            case WORKFLOW -> "workflow";
        };
    }

    /** 持久化 / API 写出：空输入保持 null。 */
    public static String toStoredWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return from(raw).wireValue();
    }
}
