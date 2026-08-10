package com.sunshine.orchestrator.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelCatalogDefinition(
        String modelName,
        String providerKey,
        String displayName,
        int contextWindow,
        int maxOutputTokens,
        String encoding,
        ModelCapabilities capabilities,
        Map<String, Object> requestExtras,
        boolean userSelectable,
        boolean enabled,
        int sortOrder
) {
}
