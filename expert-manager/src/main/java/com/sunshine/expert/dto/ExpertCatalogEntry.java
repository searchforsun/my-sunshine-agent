package com.sunshine.expert.dto;

import java.util.List;

public record ExpertCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> tags,
        String toolsJson,
        boolean enabled
) {
}
