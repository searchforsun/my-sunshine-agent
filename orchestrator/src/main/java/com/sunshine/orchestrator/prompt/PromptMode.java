package com.sunshine.orchestrator.prompt;

/**
 * PromptComposer 模式叠加层 — 对应 Nacos {@code agent.prompt.mode-overlays} 键。
 */
public enum PromptMode {
    SIMPLE_LLM("simple-llm"),
    REACT("react"),
    WORKFLOW("workflow");

    private final String overlayKey;

    PromptMode(String overlayKey) {
        this.overlayKey = overlayKey;
    }

    public String overlayKey() {
        return overlayKey;
    }
}
