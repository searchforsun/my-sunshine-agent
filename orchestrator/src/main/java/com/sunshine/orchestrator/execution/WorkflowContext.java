package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workflow 运行时变量表 - nodeId -> field -> TypedValue
 */
public class WorkflowContext {

    private static final Pattern ARRAY_INDEX = Pattern.compile("\\[([0-9]+)]");

    private final Map<String, Map<String, TypedValue>> nodes = new LinkedHashMap<>();
    private final Map<String, NodeFailureInfo> failures = new LinkedHashMap<>();

    public void putNode(String nodeId, Map<String, TypedValue> outputs) {
        if (nodeId == null || outputs == null) {
            return;
        }
        nodes.put(nodeId, new LinkedHashMap<>(outputs));
    }

    /** 测试构造用别名（等价于 putNode） */
    public void put(String nodeId, Map<String, TypedValue> outputs) {
        putNode(nodeId, outputs);
    }

    public Map<String, TypedValue> node(String nodeId) {
        return nodes.getOrDefault(nodeId, Collections.emptyMap());
    }

    /** 按插入顺序遍历节点输出 */
    public Iterable<Map.Entry<String, Map<String, TypedValue>>> nodeEntries() {
        return nodes.entrySet();
    }

    public void putNodeFailure(String nodeId, String error, int attemptCount) {
        if (nodeId == null) {
            return;
        }
        failures.put(nodeId, new NodeFailureInfo(error, attemptCount));
    }

    public NodeFailureInfo nodeFailure(String nodeId) {
        return failures.get(nodeId);
    }

    /** 解析 nodeId.path[0].field，返回 TypedValue */
    public TypedValue resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return TypedValue.fromJson(NullNode.getInstance());
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return TypedValue.fromJson(NullNode.getInstance());
        }
        String nodeId = path.substring(0, dot);
        String remaining = path.substring(dot + 1);
        if ("plan".equals(nodeId) && remaining.startsWith("params.")) {
            String paramKey = remaining.substring("params.".length());
            TypedValue val = node("plan").get(paramKey);
            return val != null ? val : TypedValue.fromJson(NullNode.getInstance());
        }
        TypedValue current = node(nodeId).get(firstSegment(remaining));
        if (current == null) {
            return TypedValue.fromJson(NullNode.getInstance());
        }
        return descend(current, remaining);
    }

    /** 解析路径并返回 render() 字符串（handler 主力取值方法） */
    public String resolvePathString(String path) {
        return resolvePath(path).render();
    }

    private static String firstSegment(String remaining) {
        int dot = remaining.indexOf('.');
        int bracket = remaining.indexOf('[');
        int end = -1;
        if (dot >= 0 && bracket >= 0) {
            end = Math.min(dot, bracket);
        } else if (dot >= 0) {
            end = dot;
        } else if (bracket >= 0) {
            end = bracket;
        }
        return end >= 0 ? remaining.substring(0, end) : remaining;
    }

    private static TypedValue descend(TypedValue current, String path) {
        if (current == null || path == null || path.isEmpty()) {
            return current != null ? current : TypedValue.fromJson(NullNode.getInstance());
        }
        // 去掉已消费的第一段
        String rest = skipFirstSegment(path);
        if (rest.isEmpty()) {
            return current;
        }
        // 处理 [index]
        Matcher m = ARRAY_INDEX.matcher(rest);
        if (rest.startsWith("[")) {
            if (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                if (current instanceof TypedValue.JsonArray arr) {
                    ArrayNode arrNode = arr.node();
                    JsonNode elem = arrNode.has(idx) ? arrNode.get(idx) : NullNode.getInstance();
                    return descend(TypedValue.fromJson(elem), rest.substring(m.end()));
                }
            }
            return TypedValue.fromJson(NullNode.getInstance());
        }
        // 处理 .field（skipFirstSegment 保留分隔符，剥掉前导 '.'）
        if (rest.startsWith(".")) {
            rest = rest.substring(1);
        }
        String field = firstSegment(rest);
        if (current instanceof TypedValue.JsonObject obj) {
            ObjectNode objNode = obj.node();
            JsonNode child = objNode.has(field) ? objNode.get(field) : NullNode.getInstance();
            return descend(TypedValue.fromJson(child), rest);
        }
        return TypedValue.fromJson(NullNode.getInstance());
    }

    private static String skipFirstSegment(String path) {
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        if (dot >= 0 && bracket >= 0) {
            return path.substring(Math.min(dot, bracket));
        }
        if (dot >= 0) {
            return path.substring(dot);
        }
        if (bracket >= 0) {
            return path.substring(bracket);
        }
        return "";
    }

    public record NodeFailureInfo(String error, int attemptCount) {
    }
}
