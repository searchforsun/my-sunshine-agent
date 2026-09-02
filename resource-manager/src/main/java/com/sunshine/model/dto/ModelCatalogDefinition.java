package com.sunshine.model.dto;

import java.util.Map;

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
