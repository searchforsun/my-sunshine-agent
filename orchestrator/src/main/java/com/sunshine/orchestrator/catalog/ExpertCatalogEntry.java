package com.sunshine.orchestrator.catalog;

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
    public String primarySkillId() {
        return skillIds != null && !skillIds.isEmpty() ? skillIds.get(0) : null;
    }
}
