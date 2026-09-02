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
    public static final String KIND_SANDBOX_SESSION = "sandbox_session";
    public static final String KIND_USAGE = "usage";

    /** 直连 Gateway / DIRECT：无分段，正文落消息底部 */
    public static StreamToken content(String text) {
        return content(text, null);
    }

    /** @param afterStepId 直连 Gateway / DIRECT 穿插锚点；ReAct 分段请用 {@link #contentInSegment} */
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

    /** 对话级沙箱就绪 — text=conversationId，channel=当前 skillId（可选） */
    public static StreamToken sandboxSession(String conversationId, String skillId) {
        return new StreamToken(KIND_SANDBOX_SESSION, conversationId, null, null, skillId, null);
    }

    /** 对话级沙箱就绪 — stepId 承载 loadedSkillIds（逗号分隔） */
    public static StreamToken sandboxSession(
            String conversationId, String currentSkillId, java.util.List<String> loadedSkillIds) {
        String loaded = loadedSkillIds == null || loadedSkillIds.isEmpty()
                ? ""
                : String.join(",", loadedSkillIds);
        return new StreamToken(KIND_SANDBOX_SESSION, conversationId, null, loaded, currentSkillId, null);
    }

    /** LLM usage 计量帧 — text 承载 wire JSON（metaUsage 原样下发，不写正文） */
    public static StreamToken usage(String usageJson) {
        return new StreamToken(KIND_USAGE, usageJson, null, null, null, null);
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

    /** content_start 的穿插锚点；未分段 plain content 的 afterStepId */
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

    public boolean isSandboxSession() {
        return KIND_SANDBOX_SESSION.equals(kind);
    }

    public boolean isUsage() {
        return KIND_USAGE.equals(kind);
    }

    public boolean isContentLifecycle() {
        return isContentStart() || isContentEnd() || (isContent() && segmentId() != null);
    }
}
