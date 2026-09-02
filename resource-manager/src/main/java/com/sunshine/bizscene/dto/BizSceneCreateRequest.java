package com.sunshine.bizscene.dto;

/**
 * 场景创建请求。{@code source=auto} 时（orchestrator 写路径 LLM 自动发现）初始
 * {@code status=pending_review}、记 {@code sourceConversationId} 溯源；缺省 manual 直接 active。
 */
public record BizSceneCreateRequest(
        String bizScene,
        String displayName,
        String description,
        String source,
        String sourceConversationId
) {
}
