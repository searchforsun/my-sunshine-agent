package com.sunshine.bizscene.dto;

public record BizSceneUpdateRequest(
        String displayName,
        String description,
        String status
) {
}
