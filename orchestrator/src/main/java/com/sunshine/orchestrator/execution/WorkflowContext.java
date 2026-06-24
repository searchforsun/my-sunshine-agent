package com.sunshine.orchestrator.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workflow 运行时变量表 — nodeId → field → value
 */
public class WorkflowContext {

    private final Map<String, Map<String, String>> nodes = new LinkedHashMap<>();
    private final Map<String, NodeFailureInfo> failures = new LinkedHashMap<>();

    public void putNode(String nodeId, Map<String, String> outputs) {
        if (nodeId == null || outputs == null) {
            return;
        }
        nodes.put(nodeId, new LinkedHashMap<>(outputs));
    }

    /** 测试与模板解析兼容别名 */
    public void put(String nodeId, Map<String, String> outputs) {
        putNode(nodeId, outputs);
    }

    public Map<String, String> node(String nodeId) {
        return nodes.getOrDefault(nodeId, Collections.emptyMap());
    }

    /** 按插入顺序遍历节点输出（answer 节点解析上游结果） */
    public Iterable<Map.Entry<String, Map<String, String>>> nodeEntries() {
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

    /** 解析 plan.params.xxx 或 nodeId.field */
    public String resolvePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return "";
        }
        String nodeId = path.substring(0, dot);
        String field = path.substring(dot + 1);
        if ("plan".equals(nodeId) && field.startsWith("params.")) {
            String paramKey = field.substring("params.".length());
            return node("plan").getOrDefault(paramKey, "");
        }
        return node(nodeId).getOrDefault(field, "");
    }

    public record NodeFailureInfo(String error, int attemptCount) {
    }
}
