package com.sunshine.agent.dto;

import java.util.List;

public record AgentCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        boolean enabled,
        String tenantId
) {
    public static AgentCatalogIndexEntry from(AgentCatalogEntry full) {
        return new AgentCatalogIndexEntry(
                full.id(), full.displayName(), full.description(), full.enabled(), full.tenantId());
    }
}
