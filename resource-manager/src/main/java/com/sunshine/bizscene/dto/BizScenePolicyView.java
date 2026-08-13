package com.sunshine.bizscene.dto;

import java.time.Instant;

public record BizScenePolicyView(
        Long policyId,
        String tenantId,
        String bizScene,
        int version,
        String status,
        String rulesJson,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant updatedAt
) {
}
