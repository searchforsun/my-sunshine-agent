package com.sunshine.orchestrator.routing;

/**
 * 顶层执行模式：fast / pro / workflow（协议 wire 唯一取值）。
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
            case "fast" -> FAST;
            case "pro" -> PRO;
            case "workflow" -> WORKFLOW;
            default -> throw new IllegalArgumentException("unknown execution mode: " + raw);
        };
    }
}
