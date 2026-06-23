package com.sunshine.orchestrator.execution;



/**

 * Workflow 节点与工作流的中文展示名（Timeline / 意图详情共用）

 */

public final class WorkflowNodeLabels {



    private static volatile WorkflowNodeLabelService service;



    private WorkflowNodeLabels() {

    }



    public static void bind(WorkflowNodeLabelService labelService) {

        service = labelService;

    }



    public static String workflowDisplayName(String workflowId) {

        if (service != null) {

            return service.workflowDisplayName(workflowId);

        }

        return workflowId != null ? workflowId : "未知工作流";

    }



    public static String displayName(String nodeId, String nodeType) {

        if (service != null) {

            return service.displayName(nodeId, nodeType);

        }

        if (nodeType != null) {
            return switch (nodeType) {
                case "rag" -> "检索知识库";
                case "llm" -> "生成回答";
                case "agent" -> "智能体分析";
                default -> nodeId != null ? nodeId : nodeType;
            };
        }

        return nodeId != null ? nodeId : nodeType;

    }



    public static String displayNameByStepId(String stepId) {

        if (stepId == null || !stepId.startsWith("node-")) {

            return stepId;

        }

        if (service != null) {

            return service.displayNameByStepId(stepId);

        }

        String nodeId = stepId.substring("node-".length());

        return switch (nodeId) {

            case "llm" -> "生成回答";

            case "rag" -> "检索知识库";

            case "agent" -> "智能体分析";

            default -> nodeId;

        };

    }



    /** 执行计划摘要：跳过 start/answer，仅展示业务节点链 */

    public static String planChain(WorkflowDefinition def) {

        if (service != null) {

            return service.planChain(def);

        }

        return "";

    }



    public static boolean isVisibleNode(String nodeType) {
        return nodeType != null && !"start".equals(nodeType);
    }

}

