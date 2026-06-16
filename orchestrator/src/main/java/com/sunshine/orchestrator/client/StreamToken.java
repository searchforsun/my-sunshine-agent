package com.sunshine.orchestrator.client;

import com.sunshine.orchestrator.agent.ProcessingStep;

/**
 * LLM 流式 token — 区分正文、推理过程、处理步骤与步骤增量
 */
public record StreamToken(
        String kind,
        String text,
        ProcessingStep step,
        String stepId,
        String channel
) {

    public static final String KIND_CONTENT = "content";
    public static final String KIND_REASONING = "reasoning";
    public static final String KIND_STEP = "step";
    public static final String KIND_STEP_DELTA = "step_delta";

    public static StreamToken content(String text) {
        return new StreamToken(KIND_CONTENT, text, null, null, null);
    }

    public static StreamToken reasoning(String text) {
        return new StreamToken(KIND_REASONING, text, null, null, null);
    }

    public static StreamToken step(ProcessingStep step) {
        return new StreamToken(KIND_STEP, null, step, null, null);
    }

    public static StreamToken stepDelta(String stepId, String channel, String text) {
        return new StreamToken(KIND_STEP_DELTA, text, null, stepId, channel);
    }

    public boolean isContent() {
        return KIND_CONTENT.equals(kind);
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
}
