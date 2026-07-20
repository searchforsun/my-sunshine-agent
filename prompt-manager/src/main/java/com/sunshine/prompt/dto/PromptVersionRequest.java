package com.sunshine.prompt.dto;

import java.time.Instant;

public record PromptVersionRequest(
        String status,
        String contentText,
        String contentJson,
        String changeNote,
        String maintainer,
        Instant expectedUpdatedAt
) {
}
