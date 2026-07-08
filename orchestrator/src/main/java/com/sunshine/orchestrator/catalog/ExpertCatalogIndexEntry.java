package com.sunshine.orchestrator.catalog;

import java.util.List;

public record ExpertCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        boolean enabled
) {
}
