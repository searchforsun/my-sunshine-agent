package com.sunshine.orchestrator.processing;

import java.util.Optional;

/**
 * 时间线标准步骤 id — 与 Nacos {@code agent.timeline.steps} 键、{@code ProcessingStep.id} 对齐。
 * 动态 id（{@code think-2}、{@code node-n1}、{@code tool-*}）不在此枚举内。
 */
public enum TimelineStepId {

    INTENT("intent"),
    PLAN("plan"),
    THINK("think"),
    GENERATE("generate"),
    RAG("rag"),
    SKILL("skill"),
    AGENT("agent"),
    TOOL("tool"),
    NODE("node"),
    TASKS("tasks");

    private final String id;

    TimelineStepId(String id) {
        this.id = id;
    }

    /** wire / 落库 / SSE step.id */
    public String id() {
        return id;
    }

    /** 标准步 phase 与 id 相同 */
    public String phase() {
        return id;
    }

    public boolean matches(String stepId) {
        return id.equals(stepId);
    }

    public static Optional<TimelineStepId> of(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return Optional.empty();
        }
        for (TimelineStepId step : values()) {
            if (step.id.equals(stepId)) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    public static boolean isNodeStep(String stepId) {
        return stepId != null && stepId.startsWith(NODE.id + "-");
    }
}
