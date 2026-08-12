package com.sunshine.auth.support;

/** 侧栏平台/对话/任务分区排布：vertical|horizontal */
public final class SidebarSectionsLayoutSupport {

    public static final String VERTICAL = "vertical";
    public static final String HORIZONTAL = "horizontal";

    private SidebarSectionsLayoutSupport() {}

    public static String from(String raw) {
        if (raw == null || raw.isBlank()) {
            return VERTICAL;
        }
        String v = raw.trim().toLowerCase();
        return switch (v) {
            case HORIZONTAL, VERTICAL -> v;
            default -> VERTICAL;
        };
    }
}
