package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypedValueTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void scalarStringRenderReturnsText() {
        TypedValue v = TypedValue.scalar("hello");
        assertThat(v.render()).isEqualTo("hello");
        assertThat(v.toJson().isTextual()).isTrue();
        assertThat(v.toJson().asText()).isEqualTo("hello");
    }

    @Test
    void scalarNumberRenderReturnsToString() {
        TypedValue v = TypedValue.scalar(42);
        assertThat(v.render()).isEqualTo("42");
        assertThat(v.toJson().isInt()).isTrue();
    }

    @Test
    void jsonObjectRenderReturnsPrettyString() {
        ObjectNode node = om.createObjectNode();
        node.put("id", "exp-001");
        node.put("amount", 100);
        TypedValue v = TypedValue.fromJson(node);
        assertThat(v.render()).contains("\"id\" : \"exp-001\"");
        assertThat(v.render()).contains("\"amount\" : 100");
    }

    @Test
    void jsonArrayRenderReturnsPrettyString() {
        var arr = om.createArrayNode();
        arr.add("a").add("b");
        TypedValue v = TypedValue.fromJson(arr);
        assertThat(v.render()).contains("\"a\"").contains("\"b\"");
    }

    @Test
    void fromJsonNullReturnsScalarNull() {
        TypedValue v = TypedValue.fromJson(om.nullNode());
        assertThat(v.render()).isEqualTo("null");
    }
}
