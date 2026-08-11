package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.DecisionStepMeta;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DecisionDtoShapeTest {
    @Test
    void option_is_id_and_label_only() {
        DecisionOption o = new DecisionOption("agent", "Agent");
        assertThat(o.id()).isEqualTo("agent");
        assertThat(o.label()).isEqualTo("Agent");
    }

    @Test
    void result_carries_answers_and_outcome() {
        DecisionAnswer a = new DecisionAnswer("q1", List.of("agent"), null);
        DecisionResult r = new DecisionResult("answered", "Need input", List.of(a), 1L);
        assertThat(r.outcome()).isEqualTo("answered");
        assertThat(r.answers()).hasSize(1);
    }

    @Test
    void step_meta_has_questions_not_flat_options() {
        DecisionQuestion q = new DecisionQuestion(
                "q1", "Mode?", List.of(new DecisionOption("a", "A"), new DecisionOption("b", "B")), false);
        DecisionStepMeta m = new DecisionStepMeta("tok", "Title", List.of(q), 9L, null, null);
        assertThat(m.questions()).hasSize(1);
        assertThat(m.title()).isEqualTo("Title");
    }
}
