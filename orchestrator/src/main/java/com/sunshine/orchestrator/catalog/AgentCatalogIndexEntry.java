package com.sunshine.orchestrator.catalog;

import java.util.List;

public record AgentCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        boolean enabled
) {
}
