package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingStepSerdeDecisionTest {

    @Test
    void serde_roundTrip_preservesDecisionMetadata() {
        DecisionStepMeta decision = new DecisionStepMeta(
                "tok-1",
                "您希望按哪种方式处理？",
                List.of(
                        new DecisionOption("plan_a", "方案A", "快", false),
                        new DecisionOption("plan_b", "方案B", "全", true)),
                false,
                1753721880000L,
                null,
                null);
        ProcessingStep step = new ProcessingStep(
                "decision-tok-1",
                "decision",
                "awaiting",
                new StepSummary(null, "等待您的决策", null),
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                1L,
                "决策",
                StepMetadata.withDecision(null, decision),
                null,
                null,
                null);

        String json = ProcessingStepSerde.toJson(List.of(step));
        assertThat(json).isNotBlank();
        List<ProcessingStep> restored = ProcessingStepSerde.fromJson(json);
        assertThat(restored).hasSize(1);
        DecisionStepMeta roundTrip = restored.get(0).metadata().decision();
        assertThat(roundTrip).isNotNull();
        assertThat(roundTrip.token()).isEqualTo("tok-1");
        assertThat(roundTrip.question()).isEqualTo("您希望按哪种方式处理？");
        assertThat(roundTrip.options()).hasSize(2);
        assertThat(roundTrip.options().get(0).requireInput()).isFalse();
        assertThat(roundTrip.options().get(1).requireInput()).isTrue();
        assertThat(roundTrip.options().get(1).value()).isEqualTo("plan_b");
        assertThat(roundTrip.options().get(1).label()).isEqualTo("方案B");
        assertThat(roundTrip.options().get(1).description()).isEqualTo("全");
        assertThat(roundTrip.allowCustomInput()).isFalse();
        assertThat(roundTrip.expiresAt()).isEqualTo(1753721880000L);
        assertThat(roundTrip.choice()).isNull();
        assertThat(roundTrip.customInput()).isNull();
    }
}
