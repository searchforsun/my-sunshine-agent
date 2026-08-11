package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void decision_roundTrip_viaMetadataToMap() {
        DecisionStepMeta decision = new DecisionStepMeta(
                "tok-sse-1",
                "您希望按哪种方式处理？",
                List.of(
                        new DecisionOption("plan_a", "方案A", "快", false),
                        new DecisionOption("plan_b", "方案B", "全", true)),
                true,
                1753721880000L,
                "plan_b",
                "补充说明：走完整流程");
        StepMetadata meta = StepMetadata.withDecision(null, decision);
        Map<String, Object> map = ProcessingStepSerde.metadataToMap(meta);

        assertThat(map).containsKey("decision");
        @SuppressWarnings("unchecked")
        Map<String, Object> decisionMap = (Map<String, Object>) map.get("decision");
        assertThat(decisionMap.get("token")).isEqualTo("tok-sse-1");
        assertThat(decisionMap.get("question")).isEqualTo("您希望按哪种方式处理？");
        assertThat(decisionMap.get("allowCustomInput")).isEqualTo(true);
        assertThat(decisionMap.get("expiresAt")).isEqualTo(1753721880000L);
        assertThat(decisionMap.get("choice")).isEqualTo("plan_b");
        assertThat(decisionMap.get("customInput")).isEqualTo("补充说明：走完整流程");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) decisionMap.get("options");
        assertThat(options).hasSize(2);
        assertThat(options.get(0).get("value")).isEqualTo("plan_a");
        assertThat(options.get(0).get("label")).isEqualTo("方案A");
        assertThat(options.get(0).get("description")).isEqualTo("快");
        assertThat(options.get(0).get("requireInput")).isEqualTo(false);
        assertThat(options.get(1).get("value")).isEqualTo("plan_b");
        assertThat(options.get(1).get("label")).isEqualTo("方案B");
        assertThat(options.get(1).get("description")).isEqualTo("全");
        assertThat(options.get(1).get("requireInput")).isEqualTo(true);

        StepMetadata parsed = ProcessingStepSerde.metadataFromMap(map);
        assertThat(parsed).isNotNull();
        assertThat(parsed.decision()).isEqualTo(decision);
    }
}
