package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/** workflow 变量值的统一类型（sealed），支持结构化 JSON 取值与 prompt 可读渲染 */
public sealed interface TypedValue permits TypedValue.Scalar, TypedValue.JsonObject, TypedValue.JsonArray {

    String render();

    JsonNode toJson();

    record Scalar(JsonNode value) implements TypedValue {
        @Override
        public String render() {
            if (value == null || value.isNull()) {
                return "null";
            }
            return value.isTextual() ? value.asText() : value.toString();
        }

        @Override
        public JsonNode toJson() {
            return value;
        }
    }

    record JsonObject(ObjectNode node) implements TypedValue {
        @Override
        public String render() {
            return node.toPrettyString();
        }

        @Override
        public JsonNode toJson() {
            return node;
        }
    }

    record JsonArray(ArrayNode node) implements TypedValue {
        @Override
        public String render() {
            return node.toPrettyString();
        }

        @Override
        public JsonNode toJson() {
            return node;
        }
    }

    static TypedValue scalar(String text) {
        return new Scalar(text != null ? TextNode.valueOf(text) : NullNode.getInstance());
    }

    static TypedValue scalar(int number) {
        return new Scalar(new IntNode(number));
    }

    static TypedValue fromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return new Scalar(NullNode.getInstance());
        }
        if (node.isObject()) {
            return new JsonObject((ObjectNode) node);
        }
        if (node.isArray()) {
            return new JsonArray((ArrayNode) node);
        }
        return new Scalar(node);
    }
}
