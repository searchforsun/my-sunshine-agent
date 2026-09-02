package com.sunshine.orchestrator.catalog;

import org.springframework.util.StringUtils;

/** 租户可见性：default（含空）全局共享；租户私有仅同租户可见（A-2，对齐 resource-manager visibleTo） */
public final class TenantVisibility {

    private TenantVisibility() {
    }

    public static String normalize(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.strip() : "default";
    }

    /** 条目租户可见于请求租户：default/空全局；否则须同租户 */
    public static boolean visible(String entryTenant, String requestTenant) {
        String entry = normalize(entryTenant);
        if ("default".equals(entry)) {
            return true;
        }
        return entry.equals(normalize(requestTenant));
    }
}
