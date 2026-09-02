package com.sunshine.model.dto;

import java.time.Instant;
import java.util.List;

public record ModelRouteResponse(
        Long id,
        String callSite,
        String label,
        String description,
        List<String> models,
        String strategy,
        boolean enabled,
        String tenantId,
        String remark,
        Instant createdAt,
        Instant updatedAt
) {
}
