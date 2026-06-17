package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.WorkflowProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.stream.Collectors;

/**
 * Workflow 目录 — 渲染进意图分类 prompt，并校验 workflowId
 */
@Component
@RequiredArgsConstructor
public class WorkflowCatalog {

    private final WorkflowProperties workflowProperties;

    /** 渲染为 markdown，注入 classifier-prompt 的 {{workflow-catalog}} */
    public String renderForPrompt() {
        if (workflowProperties.getCatalog() == null || workflowProperties.getCatalog().isEmpty()) {
            return "(无 workflow 目录配置)";
        }
        return workflowProperties.getCatalog().stream()
                .map(this::formatEntry)
                .collect(Collectors.joining("\n"));
    }

    public String renderIntoClassifier(String classifierPrompt) {
        if (!StringUtils.hasText(classifierPrompt)) {
            return classifierPrompt;
        }
        return classifierPrompt.replace("{{workflow-catalog}}", renderForPrompt());
    }

    public boolean isKnownWorkflow(String workflowId) {
        if (!StringUtils.hasText(workflowId) || workflowProperties.getCatalog() == null) {
            return false;
        }
        return workflowProperties.getCatalog().stream()
                .anyMatch(e -> workflowId.equals(e.getId()));
    }

    /** 未知 workflowId 时降级 react */
    public ExecutionPlan sanitize(ExecutionPlan plan) {
        if (plan.mode() != ExecutionMode.WORKFLOW) {
            return plan;
        }
        if (!StringUtils.hasText(plan.workflowId()) || !isKnownWorkflow(plan.workflowId())) {
            return ExecutionPlan.reactFallback(
                    "unknown workflow: " + (plan.workflowId() != null ? plan.workflowId() : "null"));
        }
        if (!workflowProperties.getDefinitions().containsKey(plan.workflowId())) {
            return ExecutionPlan.reactFallback("missing definition: " + plan.workflowId());
        }
        return plan;
    }

    private String formatEntry(WorkflowProperties.CatalogEntry e) {
        String nodes = e.getNodes() != null ? String.join(" → ", e.getNodes()) : "";
        String examples = e.getExamples() != null ? String.join("；", e.getExamples()) : "";
        return "- **" + e.getId() + "** (mode=" + e.getMode() + "): " + e.getDesc()
                + "\n  节点: " + nodes
                + (StringUtils.hasText(examples) ? "\n  示例: " + examples : "");
    }
}
