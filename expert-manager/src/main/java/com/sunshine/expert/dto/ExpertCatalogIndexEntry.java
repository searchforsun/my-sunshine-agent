package com.sunshine.expert.dto;

import java.util.List;

public record ExpertCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        boolean enabled
) {
    public static ExpertCatalogIndexEntry from(ExpertCatalogEntry full) {
        return new ExpertCatalogIndexEntry(
                full.id(), full.displayName(), full.description(), full.enabled());
    }
}
