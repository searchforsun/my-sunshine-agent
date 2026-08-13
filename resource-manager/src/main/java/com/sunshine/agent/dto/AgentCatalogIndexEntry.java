package com.sunshine.agent.dto;

import java.util.List;

public record AgentCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        boolean enabled,
        String tenantId,
        String kind
) {
    public AgentCatalogIndexEntry {
        if (kind == null || kind.isBlank()) {
            kind = "all";
        }
    }

    public static AgentCatalogIndexEntry from(AgentCatalogEntry full) {
        return new AgentCatalogIndexEntry(
                full.id(), full.displayName(), full.description(), full.enabled(), full.tenantId(), full.kind());
    }
}
