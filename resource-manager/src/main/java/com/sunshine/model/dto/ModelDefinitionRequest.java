package com.sunshine.model.dto;

import java.util.Map;

public record ModelDefinitionRequest(
        String providerKey,
        String modelName,
        String displayName,
        Integer contextWindow,
        Integer maxOutputTokens,
        String encoding,
        ModelCapabilities capabilities,
        Map<String, Object> requestExtras,
        Boolean userSelectable,
        Boolean enabled,
        Integer sortOrder,
        String tenantId
) {
}
