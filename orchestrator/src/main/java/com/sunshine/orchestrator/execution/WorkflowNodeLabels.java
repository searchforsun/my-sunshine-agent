package com.sunshine.orchestrator.execution;

/** Workflow 节点与工作流的中文展示名（Timeline / 意图详情共用） */
public final class WorkflowNodeLabels {

    private static volatile WorkflowNodeLabelService service;

    private WorkflowNodeLabels() {
    }

    public static void bind(WorkflowNodeLabelService labelService) {
        service = labelService;
    }

    public static String workflowDisplayName(String workflowId) {
        return requireService().workflowDisplayName(workflowId);
    }

    public static String displayName(String nodeId, String nodeType) {
        return requireService().displayName(nodeId, nodeType);
    }

    public static String displayNameByStepId(String stepId) {
        return requireService().displayNameByStepId(stepId);
    }

    /** 执行计划摘要：跳过 start/answer，仅展示业务节点链 */
    public static String planChain(WorkflowDefinition def) {
        return requireService().planChain(def);
    }

    /** plan 摘要链 / 主 timeline 卡片：排除 start、answer（与 PlanTimeline 一致） */
    public static boolean isPlanChainNode(String nodeType) {
        return WorkflowNodeType.isPlanChainNode(nodeType);
    }

    /** DAG node-{id} 步骤生命周期：含 answer，排除 start */
    public static boolean tracksNodeStep(String nodeType) {
        return WorkflowNodeType.tracksNodeStep(nodeType);
    }

    private static WorkflowNodeLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("WorkflowNodeLabelService 未 bind");
        }
        return service;
    }
}
