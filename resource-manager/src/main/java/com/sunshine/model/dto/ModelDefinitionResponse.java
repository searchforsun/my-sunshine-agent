package com.sunshine.model.dto;

import java.time.Instant;
import java.util.Map;

public record ModelDefinitionResponse(
        Long id,
        String providerKey,
        String modelName,
        String displayName,
        int contextWindow,
        int maxOutputTokens,
        String encoding,
        ModelCapabilities capabilities,
        Map<String, Object> requestExtras,
        boolean userSelectable,
        boolean enabled,
        int sortOrder,
        String tenantId,
        Instant createdAt,
        Instant updatedAt
) {
}
