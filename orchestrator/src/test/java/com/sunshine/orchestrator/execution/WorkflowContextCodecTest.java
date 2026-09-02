package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContextCodecTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void structuredJsonObjectRoundTripsThroughCodec() {
        // 构造含嵌套字段的 JsonObject TypedValue
        ObjectNode nested = om.createObjectNode();
        nested.put("hitCount", 3);
        nested.put("approved", true);
        nested.put("topic", "差旅报销");
        ObjectNode child = om.createObjectNode();
        child.put("docName", "policy.md");
        nested.set("firstHit", child);

        WorkflowContext ctx = new WorkflowContext();
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        outputs.put("output", TypedValue.scalar("结构化结果"));
        outputs.put("hits", TypedValue.fromJson(nested));
        ctx.putNode("rag_1", outputs);

        String json = WorkflowContextCodec.toJson(ctx);
        assertThat(json).isNotBlank();

        WorkflowContext restored = WorkflowContextCodec.fromJson(json);
        TypedValue hitsVal = restored.node("rag_1").get("hits");
        assertThat(hitsVal).isInstanceOf(TypedValue.JsonObject.class);
        ObjectNode restoredNode = ((TypedValue.JsonObject) hitsVal).node();
        assertThat(restoredNode.get("hitCount").asInt()).isEqualTo(3);
        assertThat(restoredNode.get("approved").asBoolean()).isTrue();
        assertThat(restoredNode.get("topic").asText()).isEqualTo("差旅报销");
        assertThat(restoredNode.get("firstHit").get("docName").asText()).isEqualTo("policy.md");

        // scalar 仍保持 Scalar 类型
        TypedValue outputVal = restored.node("rag_1").get("output");
        assertThat(outputVal.render()).isEqualTo("结构化结果");
    }

    @Test
    void hasNodesDetectsNonEmptyContext() {
        assertThat(WorkflowContextCodec.hasNodes(null)).isFalse();
        assertThat(WorkflowContextCodec.hasNodes("")).isFalse();
        assertThat(WorkflowContextCodec.hasNodes("{}")).isFalse();

        WorkflowContext ctx = new WorkflowContext();
        ctx.putNode("n1", Map.of("output", TypedValue.scalar("ok")));
        assertThat(WorkflowContextCodec.hasNodes(WorkflowContextCodec.toJson(ctx))).isTrue();
    }

    @Test
    void nullContextSerializesToEmptyObject() {
        assertThat(WorkflowContextCodec.toJson(null)).isEqualTo("{}");
    }
}
