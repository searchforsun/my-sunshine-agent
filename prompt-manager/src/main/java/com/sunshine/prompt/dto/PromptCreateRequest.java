package com.sunshine.prompt.dto;

public record PromptCreateRequest(
        String id,
        String kind,
        String displayName,
        String description,
        Integer priority,
        Boolean enabled,
        String status,
        String contentText,
        String contentJson,
        String changeNote,
        String maintainer
) {
}
