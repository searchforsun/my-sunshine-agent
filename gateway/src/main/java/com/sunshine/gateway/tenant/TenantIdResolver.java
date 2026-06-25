package com.sunshine.gateway.tenant;

import cn.dev33.satoken.stp.StpUtil;

/** 从 JWT extra 解析租户；未登录或缺失时返回 anonymous/default。 */
public final class TenantIdResolver {

    public static final String ANONYMOUS = "anonymous";
    public static final String DEFAULT_TENANT = "default";

    private TenantIdResolver() {
    }

    /** 已登录用户：JWT tenantId，缺省 default */
    public static String fromLogin() {
        Object tenantId = StpUtil.getExtra("tenantId");
        if (tenantId != null && !String.valueOf(tenantId).isBlank()) {
            return String.valueOf(tenantId).strip();
        }
        return DEFAULT_TENANT;
    }

    /** Gateway 限流桶：登录走 tenant，未登录走 anonymous */
    public static String forRateLimit(boolean loggedIn) {
        return loggedIn ? fromLogin() : ANONYMOUS;
    }
}
