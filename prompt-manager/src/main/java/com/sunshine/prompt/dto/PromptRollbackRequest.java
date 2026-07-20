package com.sunshine.prompt.dto;

import java.time.Instant;

public record PromptRollbackRequest(
        int version,
        Instant expectedUpdatedAt
) {
}
