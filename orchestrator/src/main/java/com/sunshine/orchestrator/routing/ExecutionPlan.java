package com.sunshine.orchestrator.routing;

import java.util.Map;

/**
 * 意图识别结果 — 路由层输出，供 ExecutionDispatcher 分发
 */
public record ExecutionPlan(
        ExecutionMode mode,
        String workflowId,
        Map<String, String> params,
        String reason,
        String ruleId
) {
    public ExecutionPlan(ExecutionMode mode, String workflowId, Map<String, String> params, String reason) {
        this(mode, workflowId, params, reason, null);
    }

    public static ExecutionPlan reactFallback(String reason) {
        return new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), reason);
    }

    /** 写入 DB / Generation / 审计的简短标签（兼容原 intent 字段） */
    public String intentLabel() {
        return switch (mode) {
            case SIMPLE_LLM -> "simple-llm";
            case WORKFLOW -> "workflow:" + (workflowId != null ? workflowId : "unknown");
            case REACT -> "react";
            case PLAN_WORKFLOW -> "plan-workflow";
        };
    }
}
