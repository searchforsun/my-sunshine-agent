package com.sunshine.bizscene.dto;

import java.time.Instant;

public record BizSceneDefinitionView(
        String bizScene,
        String displayName,
        String description,
        String status,
        String tenantId,
        String source,
        String sourceConversationId,
        String approvedBy,
        Instant approvedAt,
        Instant updatedAt
) {
}
