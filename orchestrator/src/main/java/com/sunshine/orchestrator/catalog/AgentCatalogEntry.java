package com.sunshine.orchestrator.catalog;

import java.util.List;

public record AgentCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> tags,
        String toolsJson,
        boolean enabled,
        String tenantId,
        List<String> kbScope,
        String dataScopeJson,
        String permissionsJson,
        String modelConfigJson,
        int maxIters,
        int maxHandoffs,
        AgentSource source,
        String agentCardUrl,
        String authConfigJson,
        String endpointOverride
) {
    public enum AgentSource { INTERNAL, EXTERNAL }

    public String primarySkillId() {
        return skillIds != null && !skillIds.isEmpty() ? skillIds.get(0) : null;
    }
}
