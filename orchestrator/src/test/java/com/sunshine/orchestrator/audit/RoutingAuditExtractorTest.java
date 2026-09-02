package com.sunshine.orchestrator.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingAuditExtractorTest {

    @Test
    void fromStepsJson_extractsForcedRouting() {
        String steps = """
                [{"id":"intent","metadata":{"routingReason":"user:forced-fast","skillId":"finance-analysis"}}]
                """;
        RoutingAuditExtractor.Summary summary = RoutingAuditExtractor.fromStepsJson(steps);
        assertThat(summary.routingReason()).isEqualTo("user:forced-fast");
        assertThat(summary.userForced()).isTrue();
        assertThat(summary.skillId()).isEqualTo("finance-analysis");
    }

    @Test
    void toPayloadMap_includesUserForced() {
        RoutingAuditExtractor.Summary summary = new RoutingAuditExtractor.Summary(
                "user:forced-pro", true, "finance-analysis", null);
        assertThat(RoutingAuditExtractor.toPayloadMap(summary))
                .containsEntry("routingReason", "user:forced-pro")
                .containsEntry("userForced", true)
                .containsEntry("skillId", "finance-analysis");
    }
}
