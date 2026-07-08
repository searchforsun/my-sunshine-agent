package com.sunshine.expert.dto;

import java.util.List;

public record ExpertCreateRequest(
        String id,
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds
) {
}
