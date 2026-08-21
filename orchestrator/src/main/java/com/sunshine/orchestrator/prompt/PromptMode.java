package com.sunshine.orchestrator.prompt;

/**
 * PromptComposer 模式叠加层 — 对应 Nacos {@code agent.prompt.mode-overlays} 键。
 * <p>
 * PLANNER：Planner-Executor 独立角色，仅叠 {@code planner.harness}，不叠 react/workflow 叠加层。
 */
public enum PromptMode {
    REACT("react"),
    WORKFLOW("workflow"),
    PLANNER("planner");

    private final String overlayKey;

    PromptMode(String overlayKey) {
        this.overlayKey = overlayKey;
    }

    public String overlayKey() {
        return overlayKey;
    }
}
