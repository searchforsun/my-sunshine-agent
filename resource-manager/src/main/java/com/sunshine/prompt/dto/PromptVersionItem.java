package com.sunshine.prompt.dto;

import java.time.Instant;

public record PromptVersionItem(
        int version,
        String status,
        String contentText,
        String contentJson,
        String changeNote,
        String maintainer,
        Instant createdAt
) {
}
