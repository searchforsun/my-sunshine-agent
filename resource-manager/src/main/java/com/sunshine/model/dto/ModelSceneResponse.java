package com.sunshine.model.dto;

import java.time.Instant;
import java.util.Map;

public record ModelSceneResponse(
        Long id,
        String sceneKey,
        String label,
        String description,
        String primaryModel,
        String fallbackModel,
        Map<String, Object> extras,
        boolean enabled,
        String tenantId,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {
}
