package com.sunshine.orchestrator.client;

import com.sunshine.orchestrator.agent.ProcessingStep;

/**
 * LLM 流式 token — 区分正文、推理过程、处理步骤与步骤增量。
 * ReAct 正文分段：content_start → content(segmentId) → content_end。
 */
public record StreamToken(
        String kind,
        String text,
        ProcessingStep step,
        String stepId,
        String channel,
        /** workflow agent 节点：正文分段归属 node-{id}，供抽屉 OperationStack 穿插 */
        String scopeNodeStepId
) {

    public static final String KIND_CONTENT = "content";
    public static final String KIND_CONTENT_START = "content_start";
    public static final String KIND_CONTENT_END = "content_end";
    public static final String KIND_REASONING = "reasoning";
    public static final String KIND_STEP = "step";
    public static final String KIND_STEP_DELTA = "step_delta";

    /** simple-llm：无分段，正文落消息底部 */
    public static StreamToken content(String text) {
        return content(text, null);
    }

    /** @param afterStepId simple-llm 穿插锚点；ReAct 分段请用 {@link #contentInSegment} */
    public static StreamToken content(String text, String afterStepId) {
        return new StreamToken(KIND_CONTENT, text, null, afterStepId, null, null);
    }

    public static StreamToken contentStart(String segmentId, String afterStepId) {
        return new StreamToken(KIND_CONTENT_START, null, null, afterStepId, segmentId, null);
    }

    public static StreamToken contentInSegment(String segmentId, String text) {
        return new StreamToken(KIND_CONTENT, text, null, segmentId, null, null);
    }

    public static StreamToken contentEnd(String segmentId) {
        return new StreamToken(KIND_CONTENT_END, null, null, segmentId, null, null);
    }

    public static StreamToken reasoning(String text) {
        return new StreamToken(KIND_REASONING, text, null, null, null, null);
    }

    public static StreamToken step(ProcessingStep step) {
        return new StreamToken(KIND_STEP, null, step, null, null, null);
    }

    public static StreamToken stepDelta(String stepId, String channel, String text) {
        return new StreamToken(KIND_STEP_DELTA, text, null, stepId, channel, null);
    }

    public StreamToken withScopeNodeStepId(String nodeStepId) {
        return new StreamToken(kind, text, step, stepId, channel, nodeStepId);
    }

    public boolean isContent() {
        return KIND_CONTENT.equals(kind);
    }

    public boolean isContentStart() {
        return KIND_CONTENT_START.equals(kind);
    }

    public boolean isContentEnd() {
        return KIND_CONTENT_END.equals(kind);
    }

    /** content_start → channel；content/content_end → stepId */
    public String segmentId() {
        if (isContentStart()) {
            return channel;
        }
        if (isContent() && stepId != null && stepId.startsWith("content-")) {
            return stepId;
        }
        if (isContentEnd()) {
            return stepId;
        }
        return null;
    }

    /** content_start 的穿插锚点；legacy content 的 afterStepId */
    public String afterStepId() {
        if (isContentStart()) {
            return stepId;
        }
        if (isContent() && (stepId == null || !stepId.startsWith("content-"))) {
            return stepId;
        }
        return null;
    }

    public boolean isReasoning() {
        return KIND_REASONING.equals(kind);
    }

    public boolean isStep() {
        return KIND_STEP.equals(kind);
    }

    public boolean isStepDelta() {
        return KIND_STEP_DELTA.equals(kind);
    }

    public boolean isContentLifecycle() {
        return isContentStart() || isContentEnd() || (isContent() && segmentId() != null);
    }
}
