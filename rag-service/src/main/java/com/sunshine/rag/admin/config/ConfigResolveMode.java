package com.sunshine.rag.admin.config;

/** 运行时配置读取模式（V2 SSOT） */
public enum ConfigResolveMode {
    /** 线上 Chat / POST /api/rag/search — active_published_version */
    PRODUCTION,
    /** Admin 草稿预览 */
    DRAFT,
    /** Admin 指定历史版本 */
    VERSION;

    public static ConfigResolveMode parse(String mode) {
        if (mode == null || mode.isBlank()
                || "published".equalsIgnoreCase(mode)
                || "production".equalsIgnoreCase(mode)) {
            return PRODUCTION;
        }
        if ("draft".equalsIgnoreCase(mode)) {
            return DRAFT;
        }
        if ("version".equalsIgnoreCase(mode)) {
            return VERSION;
        }
        throw new IllegalArgumentException("未知 configMode: " + mode);
    }
}
