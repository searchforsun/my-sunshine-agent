package com.sunshine.orchestrator.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepsSummaryExtractorTest {

    @Test
    void extractsToolNamesAndDuration() {
        String stepsJson = """
                [{"id":"intent","phase":"intent","lifecycle":"done","durationMs":100},
                 {"id":"tool-list_finance_messages@1710000000000","phase":"tool","lifecycle":"done","durationMs":500}]
                """;
        StepsSummaryExtractor.Summary summary = StepsSummaryExtractor.fromStepsJson(stepsJson);

        assertThat(summary.toolNames()).containsExactly("list_finance_messages");
        assertThat(summary.stepCount()).isEqualTo(2);
        assertThat(summary.totalDurationMs()).isEqualTo(600L);
    }
}
