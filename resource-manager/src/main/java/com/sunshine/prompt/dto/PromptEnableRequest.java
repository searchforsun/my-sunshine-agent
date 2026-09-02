package com.sunshine.prompt.dto;

import java.time.Instant;

public record PromptEnableRequest(
        boolean enabled,
        Instant expectedUpdatedAt
) {
}
