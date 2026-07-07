package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.orchestrator.routing.ExecutionMode;

import java.util.Optional;

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
        Optional<TimelineStepId> standard = TimelineStepId.of(stepId);
        if (standard.isEmpty()) {
            if (TimelineStepId.isNodeStep(stepId)) {
                return WorkflowNodeLabels.displayNameByStepId(stepId);
            }
            if (stepId != null && ToolStepIds.isToolStep(stepId)) {
                return ToolNodeLabels.toolLabel(stepId);
            }
            return stepId;
        }
        return switch (standard.get()) {
            case INTENT, SKILL, PLAN, THINK, GENERATE -> TimelineStepLabels.label(stepId);
            case RAG -> catalogService != null
                    ? catalogService.displayName("search_knowledge")
                    : TimelineStepLabels.label(TimelineStepId.RAG.id());
            default -> stepId;
        };
    }

    /** think / tool / node 步骤 fallback；intent/plan/rag/generate/skill 见 Nacos {@link IntentLabelService} */
    public static String beforeFor(String stepId) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepLabels.before(stepId, ExecutionMode.REACT, "", null);
        }
        if (TimelineStepId.isNodeStep(stepId)) {
            return ToolNodeLabels.nodeBefore(stepId, null, null);
        }
        if (stepId != null && ToolStepIds.isToolStep(stepId)) {
            return ToolNodeLabels.toolBefore(stepId);
        }
        return null;
    }

    public static String activeFor(String stepId) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepLabels.active(stepId, ExecutionMode.REACT, "", null);
        }
        if (TimelineStepId.isNodeStep(stepId)) {
            return ToolNodeLabels.nodeActive(stepId, null);
        }
        if (stepId != null && ToolStepIds.isToolStep(stepId)) {
            return ToolNodeLabels.toolActive(stepId);
        }
        return null;
    }

    public static String afterTemplate(String stepId, String detail) {
        if (ThinkStepIds.isThinkStep(stepId)) {
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            return ThinkStepLabels.after(stepId, ExecutionMode.REACT, "", null);
        }
        if (TimelineStepId.isNodeStep(stepId)) {
            return ToolNodeLabels.nodeAfter(stepId, detail, null);
        }
        if (stepId != null && ToolStepIds.isToolStep(stepId)) {
            return ToolNodeLabels.toolAfter(stepId, detail);
        }
        return detail;
    }

    /** 工具英文名 → 用户可读中文（前端 OperationStack 与后端 step label 共用） */
    public static String toolDisplayName(String stepId) {
        return ToolNodeLabels.toolDisplayName(stepId);
    }

    /** 工具原始输出 → 一步摘要（委托 tool-manager） */
    public static String summarizeOutput(String toolName, String text) {
        if (catalogService == null) {
            return text != null ? text.strip() : "";
        }
        return catalogService.summarizeOutput(toolName, text);
    }
}
