package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateResolverTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void resolveSimplePlaceholder() {
        var ctx = new WorkflowContext();
        ctx.putNode("start", Map.of("userQuery", TypedValue.scalar("hello")));
        assertThat(TemplateResolver.resolve("Q: {{start.userQuery}}", ctx)).isEqualTo("Q: hello");
    }

    @Test
    void resolveNestedPath() {
        var ctx = new WorkflowContext();
        ObjectNode data = om.createObjectNode();
        data.put("id", "exp-001");
        ObjectNode root = om.createObjectNode();
        root.set("data", data);
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(root)));
        assertThat(TemplateResolver.resolve("ID={{tool_1.output.data.id}}", ctx)).isEqualTo("ID=exp-001");
    }

    @Test
    void resolveArrayIndex() {
        var ctx = new WorkflowContext();
        var arr = om.createArrayNode();
        var item = om.createObjectNode();
        item.put("title", "first");
        arr.add(item);
        ObjectNode root = om.createObjectNode();
        root.set("items", arr);
        ctx.putNode("rag_1", Map.of("output", TypedValue.fromJson(root)));
        assertThat(TemplateResolver.resolve("{{rag_1.output.items[0].title}}", ctx)).isEqualTo("first");
    }

    @Test
    void resolveJsonObjectRendersToPrettyString() {
        var ctx = new WorkflowContext();
        ObjectNode data = om.createObjectNode();
        data.put("id", "exp-001");
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(data)));
        String result = TemplateResolver.resolve("data={{tool_1.output}}", ctx);
        assertThat(result).startsWith("data={");
        assertThat(result).contains("\"id\" : \"exp-001\"");
    }

    @Test
    void resolveTypedReturnsTypedValue() {
        var ctx = new WorkflowContext();
        ObjectNode data = om.createObjectNode();
        data.put("id", "exp-001");
        ObjectNode root = om.createObjectNode();
        root.set("data", data);
        ctx.putNode("tool_1", Map.of("output", TypedValue.fromJson(root)));
        TypedValue v = TemplateResolver.resolveTyped("tool_1.output.data.id", ctx);
        assertThat(v.render()).isEqualTo("exp-001");
    }

    @Test
    void resolveMissingReturnsEmptyString() {
        var ctx = new WorkflowContext();
        assertThat(TemplateResolver.resolve("{{missing.field}}", ctx)).isEqualTo("null");
    }

    @Test
    void resolveNullTemplateReturnsEmpty() {
        var ctx = new WorkflowContext();
        assertThat(TemplateResolver.resolve(null, ctx)).isEqualTo("");
    }
}
