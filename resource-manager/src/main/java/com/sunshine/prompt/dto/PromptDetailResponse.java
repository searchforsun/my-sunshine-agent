package com.sunshine.prompt.dto;

import java.time.Instant;

public record PromptDetailResponse(
        String id,
        String kind,
        String displayName,
        String description,
        boolean enabled,
        int priority,
        int activeVersion,
        long catalogVersion,
        Instant createdAt,
        Instant updatedAt,
        PromptVersionItem activeVersionContent
) {
}
