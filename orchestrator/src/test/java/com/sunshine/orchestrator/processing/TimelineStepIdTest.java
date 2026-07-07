package com.sunshine.orchestrator.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineStepIdTest {

    @Test
    void of_resolvesStandardSteps() {
        assertThat(TimelineStepId.of("generate")).contains(TimelineStepId.GENERATE);
        assertThat(TimelineStepId.of("think-2")).isEmpty();
    }

    @Test
    void isNodeStep_detectsWorkflowNodePrefix() {
        assertThat(TimelineStepId.isNodeStep("node-n1")).isTrue();
        assertThat(TimelineStepId.isNodeStep("plan")).isFalse();
    }
}
