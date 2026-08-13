package com.sunshine.bizscene.dto;

public record BizSceneCreateRequest(
        String bizScene,
        String displayName,
        String description
) {
}
