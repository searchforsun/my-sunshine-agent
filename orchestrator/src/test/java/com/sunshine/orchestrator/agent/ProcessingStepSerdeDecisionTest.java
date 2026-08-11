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
    void decision_serde_round_trips_questions() {
        DecisionQuestion q = new DecisionQuestion(
                "q1", "Mode?",
                List.of(new DecisionOption("agent", "Agent"), new DecisionOption("plan", "Plan")),
                true);
        DecisionStepMeta meta = new DecisionStepMeta("t1", "Need", List.of(q), 100L, null, null);
        StepMetadata stepMeta = StepMetadata.withDecision(null, meta);
        Map<String, Object> map = ProcessingStepSerde.metadataToMap(stepMeta);

        assertThat(map).containsKey("decision");
        @SuppressWarnings("unchecked")
        Map<String, Object> decisionMap = (Map<String, Object>) map.get("decision");
        assertThat(decisionMap.get("token")).isEqualTo("t1");
        assertThat(decisionMap.get("title")).isEqualTo("Need");
        assertThat(decisionMap).doesNotContainKeys("value", "allowCustomInput", "question", "choice");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) decisionMap.get("questions");
        assertThat(questions).hasSize(1);
        assertThat(questions.get(0).get("id")).isEqualTo("q1");
        assertThat(questions.get(0).get("prompt")).isEqualTo("Mode?");
        assertThat(questions.get(0).get("allowMultiple")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) questions.get(0).get("options");
        assertThat(options.get(0).get("id")).isEqualTo("agent");
        assertThat(options.get(0).get("label")).isEqualTo("Agent");
        assertThat(options.get(0)).doesNotContainKeys("value", "description", "requireInput");

        StepMetadata parsed = ProcessingStepSerde.metadataFromMap(map);
        DecisionStepMeta roundTrip = parsed.decision();
        assertThat(roundTrip).isNotNull();
        assertThat(roundTrip.questions()).hasSize(1);
        assertThat(roundTrip.questions().get(0).allowMultiple()).isTrue();
        assertThat(roundTrip.questions().get(0).options().get(0).id()).isEqualTo("agent");
        assertThat(roundTrip).isEqualTo(meta);
    }

    @Test
    void serde_roundTrip_preservesDecisionAnswers() {
        DecisionQuestion q = new DecisionQuestion(
                "q1", "Mode?",
                List.of(new DecisionOption("agent", "Agent"), new DecisionOption(DecisionOption.CUSTOM_ID, "自定义")),
                false);
        DecisionAnswer answer = new DecisionAnswer("q1", List.of("agent", DecisionOption.CUSTOM_ID), "extra");
        DecisionStepMeta decision = new DecisionStepMeta(
                "tok-1",
                "Need",
                List.of(q),
                1753721880000L,
                "answered",
                List.of(answer));
        ProcessingStep step = new ProcessingStep(
                "decision-tok-1",
                "decision",
                "done",
                new StepSummary(null, "已决策", null),
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
        List<ProcessingStep> restored = ProcessingStepSerde.fromJson(json);
        DecisionStepMeta roundTrip = restored.get(0).metadata().decision();
        assertThat(roundTrip.outcome()).isEqualTo("answered");
        assertThat(roundTrip.answers()).hasSize(1);
        assertThat(roundTrip.answers().get(0).questionId()).isEqualTo("q1");
        assertThat(roundTrip.answers().get(0).selectedOptionIds())
                .containsExactly("agent", DecisionOption.CUSTOM_ID);
        assertThat(roundTrip.answers().get(0).customInput()).isEqualTo("extra");
        assertThat(json).doesNotContain("\"allowCustomInput\"");
        assertThat(json).doesNotContain("\"requireInput\"");
    }
}
