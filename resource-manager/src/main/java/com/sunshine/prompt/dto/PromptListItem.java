package com.sunshine.prompt.dto;

import java.time.Instant;

public record PromptListItem(
        String id,
        String kind,
        String displayName,
        boolean enabled,
        int priority,
        int activeVersion,
        long catalogVersion,
        Instant updatedAt
) {
}
