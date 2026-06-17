package com.sunshine.orchestrator.execution.agent;

import java.util.List;

/**
 * Agent 子节点出参 — 最终答案与工具调用摘要
 */
public record AgentNodeOutput(
        String answer,
        List<String> toolCalls
) {
}
