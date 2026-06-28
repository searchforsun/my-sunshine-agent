package com.sunshine.orchestrator.processing;

/** ReAct / 子 Agent 正文分段：穿插在 timeline 步骤之后 */
public record ContentBlock(String segmentId, String afterStepId, String text) {
}
