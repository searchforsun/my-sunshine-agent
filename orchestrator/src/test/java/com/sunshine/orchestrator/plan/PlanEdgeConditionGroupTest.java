package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanEdgeConditionGroupTest {

    @Test
    void emptyGroupIsEmpty() {
        PlanEdgeConditionGroup g = PlanEdgeConditionGroup.empty();
        assertThat(g.isEmpty()).isTrue();
        assertThat(g.logic()).isEqualTo("and");
        assertThat(g.items()).isEmpty();
    }

    @Test
    void singleWrapsOneCondition() {
        PlanEdgeCondition c = new PlanEdgeCondition("{{x}}", "eq", "y");
        PlanEdgeConditionGroup g = PlanEdgeConditionGroup.single(c);
        assertThat(g.isEmpty()).isFalse();
        assertThat(g.logic()).isEqualTo("and");
        assertThat(g.items()).containsExactly(c);
    }

    @Test
    void defaultLogicIsAnd() {
        PlanEdgeConditionGroup g = new PlanEdgeConditionGroup(null, List.of());
        assertThat(g.logic()).isEqualTo("and");
    }

    @Test
    void orLogicPreserved() {
        PlanEdgeCondition c = new PlanEdgeCondition("{{x}}", "eq", "y");
        PlanEdgeConditionGroup g = new PlanEdgeConditionGroup("or", List.of(c));
        assertThat(g.logic()).isEqualTo("or");
    }
}
