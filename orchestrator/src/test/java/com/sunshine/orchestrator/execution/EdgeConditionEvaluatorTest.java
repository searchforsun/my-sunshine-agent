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

    @Test
    void gtOperatorNumericCompare() {
        var ctx = new WorkflowContext();
        ctx.putNode("rag_1", Map.of("hitCount", TypedValue.scalar(5)));
        var cond = new PlanEdgeCondition("{{rag_1.hitCount}}", "gt", "3");
        assertThat(EdgeConditionEvaluator.matches(cond, ctx)).isTrue();
    }

    @Test
    void ltOperatorNumericCompare() {
        var ctx = new WorkflowContext();
        ctx.putNode("rag_1", Map.of("hitCount", TypedValue.scalar(2)));
        var cond = new PlanEdgeCondition("{{rag_1.hitCount}}", "lt", "3");
        assertThat(EdgeConditionEvaluator.matches(cond, ctx)).isTrue();
    }

    @Test
    void gteAndLteOperators() {
        var ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("output", TypedValue.scalar(3)));
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "gte", "3"), ctx)).isTrue();
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "lte", "3"), ctx)).isTrue();
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "gte", "4"), ctx)).isFalse();
    }

    @Test
    void inOperatorEnumCheck() {
        var ctx = new WorkflowContext();
        ctx.putNode("extract_1", Map.of("result", TypedValue.scalar("approved")));
        var cond = new PlanEdgeCondition("{{extract_1.result}}", "in", "[\"approved\",\"pending\"]");
        assertThat(EdgeConditionEvaluator.matches(cond, ctx)).isTrue();
    }

    @Test
    void notInOperatorEnumCheck() {
        var ctx = new WorkflowContext();
        ctx.putNode("extract_1", Map.of("result", TypedValue.scalar("rejected")));
        var cond = new PlanEdgeCondition("{{extract_1.result}}", "not_in", "[\"approved\",\"pending\"]");
        assertThat(EdgeConditionEvaluator.matches(cond, ctx)).isTrue();
    }
}
