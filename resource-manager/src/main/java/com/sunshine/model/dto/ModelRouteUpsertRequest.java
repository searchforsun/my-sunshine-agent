package com.sunshine.model.dto;

import java.util.List;

public record ModelRouteUpsertRequest(
        String callSite,
        List<String> models,
        String strategy,
        Boolean enabled,
        String tenantId,
        String remark
) {
}
