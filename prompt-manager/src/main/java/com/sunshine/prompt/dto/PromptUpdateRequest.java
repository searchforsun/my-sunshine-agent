package com.sunshine.prompt.dto;

import java.time.Instant;

public record PromptUpdateRequest(
        String displayName,
        String description,
        Integer priority,
        Instant expectedUpdatedAt
) {
}
