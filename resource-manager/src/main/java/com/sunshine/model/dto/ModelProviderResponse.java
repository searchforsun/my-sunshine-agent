package com.sunshine.model.dto;

import java.time.Instant;

public record ModelProviderResponse(
        Long id,
        String providerKey,
        String displayName,
        String protocol,
        String baseUrl,
        String pathPrefix,
        boolean enabled,
        String tenantId,
        boolean configured,
        String apiKeyMasked,
        Instant createdAt,
        Instant updatedAt
) {
}
