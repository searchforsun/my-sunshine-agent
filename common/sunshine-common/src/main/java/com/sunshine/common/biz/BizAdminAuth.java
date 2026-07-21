package com.sunshine.common.biz;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import org.springframework.util.StringUtils;

/**
 * 业务数据 Admin CRUD 鉴权：请求头 {@value #HEADER} 须匹配配置
 * {@code sunshine.biz.admin-token}（默认 {@value #DEFAULT_TOKEN}）。
 */
public final class BizAdminAuth {

    public static final String HEADER = "X-Admin-Token";
    public static final String DEFAULT_TOKEN = "sunshine-biz-admin-dev";
    /** 供 {@code @Value} 使用的占位表达式。 */
    public static final String TOKEN_PROPERTY =
            "${sunshine.biz.admin-token:" + DEFAULT_TOKEN + "}";

    private BizAdminAuth() {
    }

    public static void require(String presented, String configured) {
        if (!StringUtils.hasText(presented) || !configured.equals(presented)) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
