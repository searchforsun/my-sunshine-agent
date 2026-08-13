package com.sunshine.bizscene.dto;

import java.time.Instant;

public record BizSceneDefinitionView(
        String bizScene,
        String displayName,
        String description,
        String status,
        String tenantId,
        Instant updatedAt
) {
}
