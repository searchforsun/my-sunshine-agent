package com.sunshine.common.tool;

/** 工具 HITL 确认默认值：读写语义与平台确认策略分离 */
public final class ToolConfirmationDefaults {

    private ToolConfirmationDefaults() {
    }

    public static boolean fromSideEffect(String sideEffect) {
        return "write".equalsIgnoreCase(sideEffect != null ? sideEffect.strip() : "");
    }
}
