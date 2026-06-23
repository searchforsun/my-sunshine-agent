package com.sunshine.orchestrator.execution;

import java.util.List;
import java.util.Map;

/**
 * 单个 DAG 节点定义（params 已解析模板；displayName 供 Timeline / Plan 图展示）
 */
public record NodeSpec(
        String id,
        String type,
        Map<String, String> params,
        String displayName
) {
    public NodeSpec(String id, String type, Map<String, String> params) {
        this(id, type, params, null);
    }
}
