package com.sunshine.orchestrator.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepDurationFinalizerTest {

    @Test
    void allocateGenerateMs_scalesWithContent() {
        assertThat(StepDurationFinalizer.allocateGenerateMs(0, 10_000)).isEqualTo(400L);
        assertThat(StepDurationFinalizer.allocateGenerateMs(200, 10_000)).isEqualTo(1000L);
        assertThat(StepDurationFinalizer.allocateGenerateMs(2000, 10_000)).isEqualTo(8000L);
    }

    @Test
    void agentEndAt_reallocatesWallTime() {
        long start = 1_000L;
        long end = start + 10_300L;
        long agentEnd = StepDurationFinalizer.agentEndAt(start, end, 800);
        long generateMs = end - agentEnd;
        assertThat(end - start).isEqualTo(agentEnd - start + generateMs);
        assertThat(generateMs).isGreaterThanOrEqualTo(800L * 5);
        assertThat(agentEnd - start).isLessThan(end - start);
    }
}
