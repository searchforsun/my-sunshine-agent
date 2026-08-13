package com.sunshine.orchestrator.catalog;

public record AgentCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        boolean enabled,
        String kind
) {
    public AgentCatalogIndexEntry {
        if (kind == null || kind.isBlank()) {
            kind = "all";
        }
    }
}
