package com.sunshine.orchestrator.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionFingerprintTest {

    @Test
    void of_hashes_title_and_questions_canonically() {
        DecisionQuestion q = new DecisionQuestion(
                "q1", "Mode?",
                List.of(new DecisionOption("agent", "Agent"), new DecisionOption("plan", "Plan")),
                true);
        String fp1 = DecisionFingerprint.of("Need", List.of(q));
        String fp2 = DecisionFingerprint.of("Need", List.of(q));
        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).matches("[0-9a-f]{64}");
    }

    @Test
    void of_differs_when_title_or_questions_change() {
        DecisionQuestion q = new DecisionQuestion(
                "q1", "Mode?",
                List.of(new DecisionOption("agent", "Agent")),
                false);
        String base = DecisionFingerprint.of("Need", List.of(q));
        assertThat(DecisionFingerprint.of("Other", List.of(q))).isNotEqualTo(base);

        DecisionQuestion q2 = new DecisionQuestion(
                "q1", "Mode?",
                List.of(new DecisionOption("agent", "Agent")),
                true);
        assertThat(DecisionFingerprint.of("Need", List.of(q2))).isNotEqualTo(base);
    }

    @Test
    void of_includes_custom_option_id() {
        DecisionQuestion q = new DecisionQuestion(
                "q1", "Pick?",
                List.of(new DecisionOption(DecisionOption.CUSTOM_ID, "自定义")),
                false);
        String fp = DecisionFingerprint.of("T", List.of(q));
        assertThat(fp).matches("[0-9a-f]{64}");
        assertThat(DecisionFingerprint.of("T", List.of())).isNotEqualTo(fp);
    }
}
