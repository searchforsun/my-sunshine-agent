package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.plan.PlanEdgeCondition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeConditionEvaluatorTest {

    @Test
    void emptyAndNotEmpty() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("output", TypedValue.scalar("")));
        ctx.putNode("n2", Map.of("output", TypedValue.scalar("hit")));
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "empty", ""), ctx)).isTrue();
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n2.output}}", "empty", ""), ctx)).isFalse();
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n2.output}}", "not_empty", ""), ctx)).isTrue();
    }

    @Test
    void containsAndEq() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("output", TypedValue.scalar("请假可报销")));
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "contains", "可报销"), ctx)).isTrue();
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "eq", "请假可报销"), ctx)).isTrue();
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "eq", "其他"), ctx)).isFalse();
    }
}
