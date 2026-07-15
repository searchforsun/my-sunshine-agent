package com.sunshine.expert.dto;

import java.util.List;

public record ExpertUpdateRequest(
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> toolIds
) {
}
