package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanTimelineTest {

    @Test
    void formatPlanDetail_includesPlanIdAndChain() {
        String detail = PlanTimeline.formatPlanDetail("abc-123", "检索 → 分析");
        assertThat(detail).isEqualTo("planId=abc-123|chain=检索 → 分析");
    }
}
