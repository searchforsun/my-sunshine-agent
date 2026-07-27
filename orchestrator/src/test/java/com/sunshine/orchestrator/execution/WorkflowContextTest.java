package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContextTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void resolvePathSimpleField() {
        var ctx = new WorkflowContext();
        ctx.putNode("start", Map.of("userQuery", TypedValue.scalar("hello")));
        TypedValue v = ctx.resolvePath("start.userQuery");
        assertThat(v.render()).isEqualTo("hello");
    }

    @Test
    void resolvePathNestedObject() {
        var ctx = new WorkflowContext();
        ObjectNode data = om.createObjectNode();
        data.put("id", "exp-001");
        ObjectNode root = om.createObjectNode();
        root.set("data", data);
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(root)));
        TypedValue v = ctx.resolvePath("tool_1.output.data.id");
        assertThat(v.render()).isEqualTo("exp-001");
    }

    @Test
    void resolvePathArrayIndex() {
        var ctx = new WorkflowContext();
        var arr = om.createArrayNode();
        var item0 = om.createObjectNode();
        item0.put("title", "first");
        arr.add(item0);
        var item1 = om.createObjectNode();
        item1.put("title", "second");
        arr.add(item1);
        ObjectNode root = om.createObjectNode();
        root.set("items", arr);
        ctx.putNode("rag_1", Map.of("hits", TypedValue.fromJson(arr)));
        TypedValue v = ctx.resolvePath("rag_1.hits[1].title");
        assertThat(v.render()).isEqualTo("second");
    }

    @Test
    void resolvePathPlanParams() {
        var ctx = new WorkflowContext();
        ctx.putNode("plan", Map.of("status", TypedValue.scalar("approved")));
        TypedValue v = ctx.resolvePath("plan.params.status");
        assertThat(v.render()).isEqualTo("approved");
    }

    @Test
    void resolvePathMissingNodeReturnsScalarNull() {
        var ctx = new WorkflowContext();
        TypedValue v = ctx.resolvePath("missing.output");
        assertThat(v.render()).isEqualTo("null");
    }

    @Test
    void resolvePathStringReturnsRender() {
        var ctx = new WorkflowContext();
        ctx.putNode("tool_1", Map.of("output", TypedValue.scalar("result text")));
        assertThat(ctx.resolvePathString("tool_1.output")).isEqualTo("result text");
    }
}
