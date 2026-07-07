package com.sunshine.orchestrator.execution;

import java.util.Optional;

/** Workflow / Plan DAG 节点 type — 与 sunshine-workflows、Planner JSON {@code type} 对齐 */
public enum WorkflowNodeType {

    START("start"),
    RAG("rag"),
    LLM("llm"),
    AGENT("agent"),
    ANSWER("answer"),
    TOOL("tool");

    private final String id;

    WorkflowNodeType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean matches(String type) {
        return id.equals(type);
    }

    public static Optional<WorkflowNodeType> of(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        for (WorkflowNodeType value : values()) {
            if (value.id.equals(type)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /** plan 摘要链：排除 start、answer */
    public static boolean isPlanChainNode(String type) {
        return type != null && !START.matches(type) && !ANSWER.matches(type);
    }

    /** DAG node-{id} 步骤生命周期：含 answer，排除 start */
    public static boolean tracksNodeStep(String type) {
        return type != null && !START.matches(type);
    }

    /** answer / llm 节点流式输出正文 */
    public static boolean isStreamingOutput(String type) {
        return ANSWER.matches(type) || LLM.matches(type);
    }

    public static java.util.Set<String> plannerTypeIds() {
        return java.util.Set.of(RAG.id, TOOL.id, AGENT.id);
    }

    public static java.util.Set<String> execTypeIds() {
        return java.util.Set.of(RAG.id, TOOL.id, AGENT.id, ANSWER.id);
    }
}
