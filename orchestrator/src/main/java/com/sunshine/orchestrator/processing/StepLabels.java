package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;

public final class StepLabels {

    private static volatile ToolCatalogService catalogService;

    private StepLabels() {
    }

    public static void bind(ToolCatalogService catalog) {
        catalogService = catalog;
    }

    public static String labelFor(String stepId) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepIds.displayLabel(stepId);
        }
        return switch (stepId) {
            case "intent" -> "识别意图";
            case "plan" -> "执行计划";
            case "rag" -> catalogService != null
                    ? catalogService.displayName("search_knowledge")
                    : "检索知识库";
            case "think" -> "规划推理";
            case "generate" -> "生成回答";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield WorkflowNodeLabels.displayNameByStepId(stepId);
                }
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "调用工具 " + toolDisplayName(stepId);
                }
                yield stepId;
            }
        };
    }

    public static String beforeFor(String stepId) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepIds.iterationOf(stepId) <= 1 ? "规划工具与作答路径" : "准备结合工具结果分析";
        }
        return switch (stepId) {
            case "intent" -> "准备识别意图";
            case "plan" -> "规划执行路径";
            case "rag" -> "准备检索知识库";
            case "think" -> "推演回答逻辑";
            case "generate" -> "组织语言，撰写回答";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield "准备" + WorkflowNodeLabels.displayNameByStepId(stepId);
                }
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "准备" + toolDisplayName(stepId);
                }
                yield null;
            }
        };
    }

    public static String activeFor(String stepId) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepIds.iterationOf(stepId) <= 1 ? "正在规划工具调用方案" : "正在综合分析工具结果";
        }
        return switch (stepId) {
            case "intent" -> "正在匹配处理方式";
            case "plan" -> "正在编排业务节点顺序";
            case "rag" -> "正在检索知识库";
            case "think" -> "正在推演作答思路";
            case "generate" -> "正在生成并输出回答";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield "正在" + WorkflowNodeLabels.displayNameByStepId(stepId);
                }
                if (stepId != null && stepId.startsWith("tool-")) {
                    yield "正在" + toolDisplayName(stepId);
                }
                yield null;
            }
        };
    }

    public static String afterTemplate(String stepId, String detail) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            return ThinkStepIds.iterationOf(stepId) <= 1 ? "工具调用方案已拟定" : "工具结果分析完成";
        }
        return switch (stepId) {
            case "intent" -> detail != null ? detail : "意图识别完成";
            case "plan" -> detail != null ? detail : "执行计划已生成";
            case "rag" -> detail;
            case "think" -> detail != null ? detail : "思考完成";
            case "generate" -> detail != null ? detail : "回答已生成";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield detail != null ? detail
                            : WorkflowNodeLabels.displayNameByStepId(stepId) + "完成";
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
        if (catalogService != null) {
            return catalogService.displayName(toolName);
        }
        return toolName;
    }
}
