package com.sunshine.orchestrator.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextPropertiesTest {

    @Test
    void l1_defaults() {
        ContextProperties.L1 l1 = new ContextProperties().getL1();
        assertThat(l1.getNearTurns()).isEqualTo(8);
        assertThat(l1.getMidTurns()).isEqualTo(8);
        assertThat(l1.getMaxTokensRatio()).isEqualTo(0.8);
        assertThat(l1.getTurnBackstop()).isEqualTo(40);
        assertThat(l1.getTokenSafetyFactor()).isEqualTo(1.1);
        assertThat(l1.getMidCompressRatio()).isEqualTo(0.15);
    }

    @Test
    void l2_newKindDefaults() {
        ContextProperties.L2 l2 = new ContextProperties().getL2();
        assertThat(l2.getMinConfidence()).isEqualTo(0.75);
        assertThat(l2.getReasoningMinConfidence()).isEqualTo(0.7);
        assertThat(l2.getInterimConclusionMinConfidence()).isEqualTo(0.6);
        assertThat(l2.getReasoningTtlDays()).isEqualTo(7);
        assertThat(l2.getOptionTtlDays()).isEqualTo(7);
        assertThat(l2.getInterimConclusionTtlDays()).isEqualTo(7);
        assertThat(l2.getTopicTtlDays()).isEqualTo(1);
    }

    @Test
    void l3_decayHalfLifeDaysDefault() {
        ContextProperties.L3 l3 = new ContextProperties().getL3();
        assertThat(l3.getTopK()).isEqualTo(5);
        assertThat(l3.getMinScore()).isEqualTo(0.55);
        assertThat(l3.getDecayHalfLifeDays()).isEqualTo(90);
    }
}
