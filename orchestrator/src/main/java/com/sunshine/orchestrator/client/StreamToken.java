package com.sunshine.orchestrator.client;

import com.sunshine.orchestrator.agent.ProcessingStep;

/**
 * LLM 流式 token — 区分正文、推理过程与处理步骤
 */
public record StreamToken(String kind, String text, ProcessingStep step) {

    public static final String KIND_CONTENT = "content";
    public static final String KIND_REASONING = "reasoning";
    public static final String KIND_STEP = "step";

    public static StreamToken content(String text) {
        return new StreamToken(KIND_CONTENT, text, null);
    }

    public static StreamToken reasoning(String text) {
        return new StreamToken(KIND_REASONING, text, null);
    }

    public static StreamToken step(ProcessingStep step) {
        return new StreamToken(KIND_STEP, null, step);
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
}
