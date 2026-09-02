package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WorkflowContext 节点输出快照序列化（暂停续跑）。
 * TypedValue 序列化为 JSON，反序列化时用 {@link TypedValue#fromJson(JsonNode)} 重建。
 */
public final class WorkflowContextCodec {

    private static final ObjectMapper OM = new ObjectMapper();

    private WorkflowContextCodec() {
    }

    public static String toJson(WorkflowContext ctx) {
        if (ctx == null) {
            return "{}";
        }
        try {
            // nodes: {nodeId: {field: jsonValue}}
            Map<String, Map<String, JsonNode>> nodes = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, TypedValue>> e : ctx.nodeEntries()) {
                Map<String, JsonNode> fields = new LinkedHashMap<>();
                if (e.getValue() != null) {
                    e.getValue().forEach((k, v) -> {
                        if (k != null && v != null) {
                            fields.put(k, v.toJson());
                        }
                    });
                }
                nodes.put(e.getKey(), fields);
            }
            return OM.writeValueAsString(Map.of("nodes", nodes));
        } catch (Exception e) {
            return "{}";
        }
    }

    public static WorkflowContext fromJson(String json) {
        WorkflowContext ctx = new WorkflowContext();
        if (json == null || json.isBlank()) {
            return ctx;
        }
        try {
            Map<String, Object> root = OM.readValue(json, new TypeReference<>() {
            });
            Object nodesObj = root.get("nodes");
            if (!(nodesObj instanceof Map<?, ?> nodes)) {
                return ctx;
            }
            for (Map.Entry<?, ?> entry : nodes.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> fields)) {
                    continue;
                }
                Map<String, TypedValue> outputs = new LinkedHashMap<>();
                for (Map.Entry<?, ?> field : fields.entrySet()) {
                    if (field.getKey() == null || field.getValue() == null) {
                        continue;
                    }
                    // 反序列化时 JSON value 用 readTree 重建为 JsonNode 再包 TypedValue
                    JsonNode node = OM.valueToTree(field.getValue());
                    outputs.put(field.getKey().toString(), TypedValue.fromJson(node));
                }
                ctx.putNode(entry.getKey().toString(), outputs);
            }
        } catch (Exception ignored) {
            // 空上下文续跑
        }
        return ctx;
    }

    /** 检查点是否含至少一个节点输出（非空 wfCtx） */
    public static boolean hasNodes(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.strip())) {
            return false;
        }
        try {
            Map<String, Object> root = OM.readValue(json, new TypeReference<>() {
            });
            Object nodesObj = root.get("nodes");
            if (!(nodesObj instanceof Map<?, ?> nodes) || nodes.isEmpty()) {
                return false;
            }
            for (Object value : nodes.values()) {
                if (value instanceof Map<?, ?> fields && !fields.isEmpty()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }
}
