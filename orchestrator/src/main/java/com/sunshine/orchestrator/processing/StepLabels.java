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
            case "skill" -> "加载技能";
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
                    yield toolInvokeLabel(stepId);
                }
                if (stepId != null && (stepId.equals("rag") || stepId.startsWith("rag@"))) {
                    yield toolInvokeLabel(stepId);
                }
                yield stepId;
            }
        };
    }

    /** think / tool / node 步骤 fallback；intent/plan/rag/generate/skill 见 Nacos {@link IntentLabelService} */
    public static String beforeFor(String stepId) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepIds.iterationOf(stepId) <= 1 ? "规划工具与作答路径" : "准备结合工具结果分析";
        }
        if (stepId != null && stepId.startsWith("node-")) {
            return "准备" + WorkflowNodeLabels.displayNameByStepId(stepId);
        }
        if (stepId != null && stepId.startsWith("tool-")) {
            return "准备" + toolDisplayName(stepId);
        }
        return null;
    }

    public static String activeFor(String stepId) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepIds.iterationOf(stepId) <= 1 ? "正在规划工具调用方案" : "正在综合分析工具结果";
        }
        if (stepId != null && stepId.startsWith("node-")) {
            return "正在" + WorkflowNodeLabels.displayNameByStepId(stepId);
        }
        if (stepId != null && stepId.startsWith("tool-")) {
            return "正在" + toolDisplayName(stepId);
        }
        return null;
    }

    public static String afterTemplate(String stepId, String detail) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            return ThinkStepIds.iterationOf(stepId) <= 1 ? "工具调用方案已拟定" : "工具结果分析完成";
        }
        if (stepId != null && stepId.startsWith("node-")) {
            return detail != null ? detail
                    : WorkflowNodeLabels.displayNameByStepId(stepId) + "完成";
        }
        if (stepId != null && stepId.startsWith("tool-")) {
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            return toolDisplayName(stepId) + "完成";
        }
        return detail;
    }

    /** 工具步骤标题（时间戳仅用于 stepId 去重，不展示在 label） */
    private static String toolInvokeLabel(String stepId) {
        return "调用工具 " + toolDisplayName(stepId);
    }

    /** 工具英文名 → 用户可读中文（前端 OperationStack 与后端 step label 共用） */
    public static String toolDisplayName(String stepId) {
        if (stepId == null) {
            return "";
        }
        String toolName = ToolStepIds.catalogToolName(stepId);
        if (catalogService != null) {
            return catalogService.displayName(toolName);
        }
        return toolName != null ? toolName : stepId;
    }
}
