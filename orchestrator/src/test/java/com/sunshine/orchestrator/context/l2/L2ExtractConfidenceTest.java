package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class L2ExtractConfidenceTest {

    private final ContextProperties.L2 l2 = new ContextProperties.L2();

    @Test
    void minConfidenceFor_originalKinds_usesDefault() {
        assertThat(L2ExtractService.minConfidenceFor("profile", l2)).isEqualTo(0.75);
        assertThat(L2ExtractService.minConfidenceFor("decision", l2)).isEqualTo(0.75);
        assertThat(L2ExtractService.minConfidenceFor("constraint", l2)).isEqualTo(0.75);
    }

    @Test
    void minConfidenceFor_reasoningAndOption_uses070() {
        assertThat(L2ExtractService.minConfidenceFor("reasoning", l2)).isEqualTo(0.7);
        assertThat(L2ExtractService.minConfidenceFor("option", l2)).isEqualTo(0.7);
    }

    @Test
    void minConfidenceFor_interimConclusion_uses060() {
        assertThat(L2ExtractService.minConfidenceFor("interim_conclusion", l2)).isEqualTo(0.6);
    }

    @Test
    void minConfidenceFor_topic_noGate() {
        assertThat(L2ExtractService.minConfidenceFor("topic", l2)).isEqualTo(0.0);
    }

    @Test
    void minConfidenceFor_todo_usesDefault() {
        assertThat(L2ExtractService.minConfidenceFor("todo", l2)).isEqualTo(0.75);
    }

    @Test
    void ttlDays_newKinds() {
        ContextProperties.L2 props = new ContextProperties.L2();
        assertThat(L2StateStore.ttlDays("reasoning", props)).isEqualTo(7);
        assertThat(L2StateStore.ttlDays("option", props)).isEqualTo(7);
        assertThat(L2StateStore.ttlDays("interim_conclusion", props)).isEqualTo(7);
        assertThat(L2StateStore.ttlDays("topic", props)).isEqualTo(1);
    }
}
