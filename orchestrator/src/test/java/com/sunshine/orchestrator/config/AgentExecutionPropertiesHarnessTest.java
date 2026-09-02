package com.sunshine.orchestrator.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionPropertiesHarnessTest {
    @Test
    void harnessDefaultsMatchLongLoadV7() {
        AgentExecutionProperties props = new AgentExecutionProperties();
        var h = props.getHarness();
        assertThat(h.isEnabled()).isFalse();
        assertThat(h.getMaxRounds()).isEqualTo(12);
        assertThat(h.getMaxTotalTasks()).isEqualTo(24);
        assertThat(h.getMaxDurationMs()).isEqualTo(14_400_000L);
        assertThat(h.getPlanner().getTimeoutMs()).isEqualTo(300_000L);
        assertThat(h.getPlanner().getMaxIters()).isEqualTo(30);
        assertThat(h.getWorker().getTimeoutMs()).isEqualTo(3_600_000L);
        assertThat(h.getNotebook().getRedisTtlSeconds()).isEqualTo(604_800L);
        assertThat(h.getNotebook().getKeyPrefix()).isEqualTo("sunshine:plan:notebook:");
        assertThat(h.getNotebook().getCompression().getNearKeepRounds()).isEqualTo(10);
    }
}
