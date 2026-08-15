package com.sunshine.agent.dto;

public record AgentCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        boolean enabled,
        String tenantId,
        String kind,
        String bizScene,
        String toolsJson
) {
    public AgentCatalogIndexEntry {
        if (kind == null || kind.isBlank()) {
            kind = "all";
        }
    }

    public static AgentCatalogIndexEntry from(AgentCatalogEntry full) {
        return new AgentCatalogIndexEntry(
                full.id(), full.displayName(), full.description(), full.enabled(), full.tenantId(),
                full.kind(), full.bizScene(), full.toolsJson());
    }
}
