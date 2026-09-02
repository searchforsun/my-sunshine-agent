package com.sunshine.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelCatalogProvider(
        String providerKey,
        String displayName,
        String protocol,
        String baseUrl,
        String pathPrefix,
        boolean enabled,
        String apiKeyEnc
) {
}
