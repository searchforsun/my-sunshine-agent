package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JoinNodeHandlerTest {

    private final ObjectMapper om = new ObjectMapper();
    private final JoinNodeHandler handler = new JoinNodeHandler();

    private static ExecutionStreamContext newStreamCtx() {
        return new ExecutionStreamContext(
                "c1", "m1", "汇总", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.PRO, "dynamic", Map.of(), "test"));
    }

    private void putBranch(WorkflowContext ctx, String nodeId, String text) {
        ctx.putNode(nodeId, Map.of("output", TypedValue.scalar(text)));
    }

    @Test
    void collectStrategyAggregatesBranchesIntoArray() {
        WorkflowContext ctx = new WorkflowContext();
        putBranch(ctx, "b1", "甲");
        putBranch(ctx, "b2", "乙");
        NodeSpec spec = new NodeSpec("join-1", "join",
                Map.of("mergeStrategy", "collect", "branches", "b1,b2"),
                "汇总");
        NodeResult result = handler.run(spec, ctx, newStreamCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        TypedValue output = result.safeOutputs().get("output");
        assertThat(output).isInstanceOf(TypedValue.JsonArray.class);
        assertThat(((TypedValue.JsonArray) output).node().size()).isEqualTo(2);
        assertThat(result.safeOutputs().get("status").render()).isEqualTo("joined");
    }

    @Test
    void firstStrategyTakesFirstNonNullBranch() {
        WorkflowContext ctx = new WorkflowContext();
        putBranch(ctx, "b1", "甲");
        putBranch(ctx, "b2", "乙");
        NodeSpec spec = new NodeSpec("join-1", "join",
                Map.of("mergeStrategy", "first", "branches", "b1,b2"),
                "取首");
        NodeResult result = handler.run(spec, ctx, newStreamCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output").render()).contains("甲");
    }

    @Test
    void lastStrategyTakesLastBranch() {
        WorkflowContext ctx = new WorkflowContext();
        putBranch(ctx, "b1", "甲");
        putBranch(ctx, "b2", "乙");
        NodeSpec spec = new NodeSpec("join-1", "join",
                Map.of("mergeStrategy", "last", "branches", "b1,b2"),
                "取尾");
        NodeResult result = handler.run(spec, ctx, newStreamCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output").render()).contains("乙");
    }

    @Test
    void mergeStrategyMergesObjectFields() {
        WorkflowContext ctx = new WorkflowContext();
        ObjectNode obj1 = om.createObjectNode();
        obj1.put("a", "1");
        ObjectNode obj2 = om.createObjectNode();
        obj2.put("b", "2");
        ctx.putNode("b1", Map.of("output", TypedValue.fromJson(obj1)));
        ctx.putNode("b2", Map.of("output", TypedValue.fromJson(obj2)));
        NodeSpec spec = new NodeSpec("join-1", "join",
                Map.of("mergeStrategy", "merge", "branches", "b1,b2"),
                "合并");
        NodeResult result = handler.run(spec, ctx, newStreamCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        TypedValue output = result.safeOutputs().get("output");
        assertThat(output).isInstanceOf(TypedValue.JsonObject.class);
        assertThat(((TypedValue.JsonObject) output).node().has("a")).isTrue();
        assertThat(((TypedValue.JsonObject) output).node().has("b")).isTrue();
    }

    @Test
    void defaultStrategyIsCollect() {
        WorkflowContext ctx = new WorkflowContext();
        putBranch(ctx, "b1", "甲");
        NodeSpec spec = new NodeSpec("join-1", "join",
                Map.of("branches", "b1"),
                "默认汇总");
        NodeResult result = handler.run(spec, ctx, newStreamCtx()).block();
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.safeOutputs().get("output")).isInstanceOf(TypedValue.JsonArray.class);
    }
}
