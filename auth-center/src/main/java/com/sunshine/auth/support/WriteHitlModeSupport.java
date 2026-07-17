package com.sunshine.auth.support;

/** 沙箱写 HITL 用户默认：never|always|smart */
public final class WriteHitlModeSupport {

    public static final String NEVER = "never";
    public static final String ALWAYS = "always";
    public static final String SMART = "smart";

    private WriteHitlModeSupport() {}

    public static String from(String raw) {
        if (raw == null || raw.isBlank()) {
            return NEVER;
        }
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case ALWAYS, SMART, NEVER -> v;
            default -> NEVER;
        };
    }
}
