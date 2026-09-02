package com.sunshine.orchestrator.context.l2;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ContextWritePolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class L2ExtractConfidenceTest {

    private final ContextProperties.L2 l2 = new ContextProperties.L2();

    @Test
    void minConfidenceFor_originalKinds_usesDefault() {
        assertThat(ContextWritePolicy.l2MinConfidenceFor("profile", l2)).isEqualTo(0.75);
        assertThat(ContextWritePolicy.l2MinConfidenceFor("decision", l2)).isEqualTo(0.75);
        assertThat(ContextWritePolicy.l2MinConfidenceFor("constraint", l2)).isEqualTo(0.75);
    }

    @Test
    void minConfidenceFor_processNote_uses065() {
        assertThat(ContextWritePolicy.l2MinConfidenceFor("process_note", l2)).isEqualTo(0.65);
    }

    @Test
    void minConfidenceFor_mergedAwayKinds_fallBackToDefault() {
        // 精简后旧 kind（reasoning/option/interim_conclusion/topic）不再存在于白名单，走 default 0.75
        assertThat(ContextWritePolicy.l2MinConfidenceFor("reasoning", l2)).isEqualTo(0.75);
        assertThat(ContextWritePolicy.l2MinConfidenceFor("topic", l2)).isEqualTo(0.75);
    }

    @Test
    void minConfidenceFor_todo_usesDefault() {
        assertThat(ContextWritePolicy.l2MinConfidenceFor("todo", l2)).isEqualTo(0.75);
    }

    @Test
    void ttlDays_processNote_uses7() {
        ContextProperties.L2 props = new ContextProperties.L2();
        assertThat(ContextWritePolicy.l2TtlDays("process_note", props)).isEqualTo(7);
        assertThat(ContextWritePolicy.l2TtlDays("todo", props)).isEqualTo(7);
    }
}
