package com.sunshine.prompt.dto;

import java.time.Instant;

public record PromptPublishRequest(
        Integer version,
        String maintainer,
        Instant expectedUpdatedAt
) {
}
