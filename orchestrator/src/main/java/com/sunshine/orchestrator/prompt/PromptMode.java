package com.sunshine.orchestrator.prompt;

/**
 * PromptComposer 模式叠加层 — 对应 Nacos {@code agent.prompt.mode-overlays} 键。
 */
public enum PromptMode {
    /** 直连 Gateway / DIRECT（无工具） */
    DIRECT("direct"),
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
