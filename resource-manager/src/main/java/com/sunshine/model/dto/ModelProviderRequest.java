package com.sunshine.model.dto;

public record ModelProviderRequest(
        String providerKey,
        String displayName,
        String protocol,
        String baseUrl,
        String pathPrefix,
        String apiKey,
        Boolean enabled,
        String tenantId
) {
}
