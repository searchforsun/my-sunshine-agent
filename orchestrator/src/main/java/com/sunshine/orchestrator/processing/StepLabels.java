package com.sunshine.orchestrator.processing;

import java.util.Map;

public final class StepLabels {

    private static final Map<String, String> TOOL_DISPLAY_NAMES = Map.of(
            "list_finance_messages", "查询待审批财务消息",
            "search_knowledge", "检索知识库"
    );

    private StepLabels() {
    }

    public static String labelFor(String stepId) {
        return switch (stepId) {
            case "intent" -> "识别意图";
            case "plan" -> "执行计划";
            case "rag" -> "检索知识库";
            case "think" -> "思考过程";
            case "generate" -> "生成回答";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield "执行节点 " + stepId.substring("node-".length());
                }
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "调用工具 " + toolDisplayName(stepId);
                }
                yield stepId;
            }
        };
    }

    public static String beforeFor(String stepId) {
        return switch (stepId) {
            case "intent" -> "准备识别意图";
            case "plan" -> "准备执行工作流";
            case "rag" -> "准备检索向量库";
            case "think" -> "推演回答逻辑";
            case "generate" -> "组织语言，撰写回答";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield "准备节点 " + stepId.substring("node-".length());
                }
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "准备" + toolDisplayName(stepId);
                }
                yield null;
            }
        };
    }

    public static String activeFor(String stepId) {
        return switch (stepId) {
            case "intent" -> "正在分析用户输入";
            case "plan" -> "正在规划节点执行顺序";
            case "rag" -> "正在查询 Milvus";
            case "think" -> "正在推演作答思路";
            case "generate" -> "正在生成并输出回答";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield "正在执行节点 " + stepId.substring("node-".length());
                }
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "正在" + toolDisplayName(stepId);
                }
                yield null;
            }
        };
    }

    public static String afterTemplate(String stepId, String detail) {
        return switch (stepId) {
            case "intent" -> detail != null ? "判定为：" + detail : "判定完成";
            case "plan" -> detail != null ? detail : "计划已生成";
            case "rag" -> detail;
            case "think" -> detail != null ? detail : "思考完成";
            case "generate" -> detail != null ? detail : "回答已生成";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield detail != null ? detail : "节点 " + stepId.substring("node-".length()) + " 完成";
                }
                if (stepId != null && stepId.startsWith("tool-")) {
                    if (detail != null && !detail.isBlank()) {
                        yield detail;
                    }
                    yield toolDisplayName(stepId) + "完成";
                }
                yield detail;
            }
        };
    }

    /** 工具英文名 → 用户可读中文（前端 OperationStack 与后端 step label 共用） */
    public static String toolDisplayName(String stepId) {
        if (stepId == null) {
            return "";
        }
        String toolName = stepId.startsWith("tool-") ? stepId.substring("tool-".length()) : stepId;
        return TOOL_DISPLAY_NAMES.getOrDefault(toolName, toolName);
    }

    private static String toolName(String stepId) {
        return toolDisplayName(stepId);
    }
}
