package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.plan.PlanEdgeCondition;
import com.sunshine.orchestrator.plan.PlanEdgeConditionGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    void notEqOperator() {
        var ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("output", TypedValue.scalar("done")));
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "not_eq", "done"), ctx)).isFalse();
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "not_eq", "pending"), ctx)).isTrue();
    }

    @Test
    void notContainsOperator() {
        var ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("output", TypedValue.scalar("已完成")));
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "not_contains", "已完成"), ctx)).isFalse();
        assertThat(EdgeConditionEvaluator.matches(
                new PlanEdgeCondition("{{n1.output}}", "not_contains", "待处理"), ctx)).isTrue();
    }

    @Test
    void matchesGroupAndAllTrue() {
        var ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("count", TypedValue.scalar(5)));
        ctx.putNode("n2", Map.of("status", TypedValue.scalar("running")));
        var group = new PlanEdgeConditionGroup("and", List.of(
                new PlanEdgeCondition("{{n1.count}}", "gt", "3"),
                new PlanEdgeCondition("{{n2.status}}", "not_eq", "done")));
        assertThat(EdgeConditionEvaluator.matchesGroup(group, ctx)).isTrue();
    }

    @Test
    void matchesGroupAndOneFalse() {
        var ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("count", TypedValue.scalar(2)));
        ctx.putNode("n2", Map.of("status", TypedValue.scalar("running")));
        var group = new PlanEdgeConditionGroup("and", List.of(
                new PlanEdgeCondition("{{n1.count}}", "gt", "3"),
                new PlanEdgeCondition("{{n2.status}}", "not_eq", "done")));
        assertThat(EdgeConditionEvaluator.matchesGroup(group, ctx)).isFalse();
    }

    @Test
    void matchesGroupOrOneTrue() {
        var ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("count", TypedValue.scalar(2)));
        ctx.putNode("n2", Map.of("status", TypedValue.scalar("done")));
        var group = new PlanEdgeConditionGroup("or", List.of(
                new PlanEdgeCondition("{{n1.count}}", "gt", "3"),
                new PlanEdgeCondition("{{n2.status}}", "not_eq", "done")));
        assertThat(EdgeConditionEvaluator.matchesGroup(group, ctx)).isFalse();
    }

    @Test
    void matchesGroupEmptyReturnsTrue() {
        var ctx = new WorkflowContext();
        var group = PlanEdgeConditionGroup.empty();
        assertThat(EdgeConditionEvaluator.matchesGroup(group, ctx)).isTrue();
    }
}
