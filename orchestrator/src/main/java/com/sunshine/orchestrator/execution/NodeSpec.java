package com.sunshine.orchestrator.execution;

import java.util.List;
import java.util.Map;

/**
 * 单个 DAG 节点定义（params 已解析模板；inputs 为显式输入绑定；displayName 供 Timeline / Plan 图展示）
 */
public record NodeSpec(
        String id,
        String type,
        Map<String, Object> params,
        List<InputBinding> inputs,
        String displayName
) {
    public NodeSpec(String id, String type, Map<String, Object> params) {
        this(id, type, params, List.of(), null);
    }

    public NodeSpec(String id, String type, Map<String, Object> params, String displayName) {
        this(id, type, params, List.of(), displayName);
    }
}
