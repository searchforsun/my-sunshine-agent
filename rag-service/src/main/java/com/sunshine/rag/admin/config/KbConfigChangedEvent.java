package com.sunshine.rag.admin.config;

/** publish / activate 后通知运行时缓存失效 */
public record KbConfigChangedEvent(String tenantId, String kbId) {
}
