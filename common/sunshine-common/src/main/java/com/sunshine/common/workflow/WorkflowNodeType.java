package com.sunshine.common.workflow;

import java.util.Optional;
import java.util.Set;

/**
 * Workflow / Plan DAG 节点 type SSOT（Studio DB PlanJson · Planner · 执行引擎共用）。
 * 前端可加类型须与本枚举 id 对齐（见 sunshine-ui workflowGateway / workflowPlan）。
 */
public enum WorkflowNodeType {

    START("start"),
    RAG("rag"),
    LLM("llm"),
    AGENT("agent"),
    ANSWER("answer"),
    TOOL("tool"),
    JOIN("join"),
    PARALLEL_GATEWAY("parallel-gateway"),
    EXCLUSIVE_GATEWAY("exclusive-gateway"),
    LOOP("loop");

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

    /** Studio / DB 合法 type（含 start） */
    public static Set<String> studioTypeIds() {
        return Set.of(
                START.id, RAG.id, LLM.id, AGENT.id, ANSWER.id, TOOL.id, JOIN.id,
                PARALLEL_GATEWAY.id, EXCLUSIVE_GATEWAY.id, LOOP.id);
    }

    /**
     * Plan-workflow normalize 后节点 type（无 start 节点；start 仅作边源）。
     */
    public static Set<String> planExecTypeIds() {
        return Set.of(
                RAG.id, TOOL.id, AGENT.id, ANSWER.id, JOIN.id,
                PARALLEL_GATEWAY.id, EXCLUSIVE_GATEWAY.id, LOOP.id);
    }

    /** Planner 允许产出的业务 type */
    public static Set<String> plannerTypeIds() {
        return Set.of(RAG.id, TOOL.id, AGENT.id);
    }

    /** 业务节点（含遗留 llm） */
    public static Set<String> businessTypeIds() {
        return Set.of(RAG.id, TOOL.id, AGENT.id, LLM.id);
    }

    /** loop 容器内允许的 body type */
    public static Set<String> loopBodyTypeIds() {
        return Set.of(RAG.id, TOOL.id, AGENT.id);
    }

    /** 无业务输出的路由节点（不可作 {{node.output}} 源） */
    public static Set<String> routingOnlyTypeIds() {
        return Set.of(PARALLEL_GATEWAY.id, EXCLUSIVE_GATEWAY.id);
    }

    /** 可作为 {{node.output}} / {{node.answer}} 引用源 */
    public static Set<String> outputTypeIds() {
        return Set.of(RAG.id, TOOL.id, JOIN.id, LLM.id, AGENT.id, LOOP.id);
    }

    /** plan 摘要链：排除 start、answer、join 与 BPMN 网关 */
    public static boolean isPlanChainNode(String type) {
        return type != null && !START.matches(type) && !ANSWER.matches(type) && !JOIN.matches(type)
                && !PARALLEL_GATEWAY.matches(type) && !EXCLUSIVE_GATEWAY.matches(type);
    }

    /** DAG node-{id} 步骤：含 answer / loop；路由网关/join 仅执行不落主时间线 */
    public static boolean tracksNodeStep(String type) {
        return type != null && !START.matches(type) && !JOIN.matches(type)
                && !PARALLEL_GATEWAY.matches(type) && !EXCLUSIVE_GATEWAY.matches(type);
    }

    /** answer / llm 节点流式输出正文 */
    public static boolean isStreamingOutput(String type) {
        return ANSWER.matches(type) || LLM.matches(type);
    }

    public static boolean isLoopBodyType(String type) {
        return loopBodyTypeIds().contains(type);
    }
}
