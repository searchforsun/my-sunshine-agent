package com.sunshine.bizscene.dto;

import java.time.Instant;

public record BizScenePolicySaveRequest(
        String bizScene,
        String rulesJson,
        Instant effectiveFrom,
        Instant effectiveTo
) {
}
