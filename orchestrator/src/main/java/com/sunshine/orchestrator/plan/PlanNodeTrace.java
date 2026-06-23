package com.sunshine.orchestrator.plan;

/** 节点执行摘要 — 写入 execution_trace JSON 数组；detail 为展开区正文（answer/llm 全文等） */
public record PlanNodeTrace(
        String nodeId,
        String type,
        String status,
        String summary,
        String detail,
        Long startedAt,
        Long endedAt
) {
}
