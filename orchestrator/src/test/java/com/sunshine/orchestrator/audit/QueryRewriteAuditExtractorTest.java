package com.sunshine.orchestrator.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteAuditExtractorTest {

    @Test
    void extractsRewriteFieldsFromStepsMetadata() {
        String stepsJson = """
                [
                  {"id":"intent","metadata":{"rewriteApplied":true,"rewriteLatencyMs":11,
                    "rewriteFrom":"待审批","rewriteTo":"查询待审批报销","rewriteScenario":"intent"}},
                  {"id":"node-rag","metadata":{"hitCount":2,"rewriteApplied":true,"rewriteLatencyMs":9,
                    "rewriteFrom":"年假","rewriteTo":"公司年假制度","rewriteScenario":"rag"}}
                ]
                """;

        QueryRewriteAuditExtractor.Summary summary = QueryRewriteAuditExtractor.fromStepsJson(stepsJson);

        assertThat(summary.rewriteApplied()).isTrue();
        assertThat(summary.rewriteLatencyMs()).isEqualTo(20L);
        assertThat(summary.rewrites()).hasSize(2);
        assertThat(summary.rewrites().get(0)).containsEntry("scenario", "intent");
    }
}
