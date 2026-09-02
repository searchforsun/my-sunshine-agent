package com.sunshine.model.dto;

import java.util.Map;

public record ModelSceneUpsertRequest(
        String sceneKey,
        String primaryModel,
        String fallbackModel,
        Map<String, Object> extras,
        Boolean enabled,
        String tenantId,
        String remark
) {
}
