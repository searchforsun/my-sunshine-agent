package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.catalog.ResourceKindFilter;
import com.sunshine.orchestrator.catalog.WorkflowCatalogRegistry;
import com.sunshine.orchestrator.client.WorkflowManagerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.stream.Collectors;

/** Workflow 目录 — 渲染进意图分类 prompt，并校验 workflowId（DB SSOT） */
@Component
@RequiredArgsConstructor
public class WorkflowCatalog {

    private final WorkflowCatalogRegistry catalogRegistry;
    private final WorkflowManagerClient workflowManagerClient;

    /** 渲染全部 workflow（Planner 参考目录，不做会话形态裁剪） */
    public String renderForPrompt() {
        return renderForPrompt(null);
    }

    /** 按会话 kind 过滤渲染（保留 all + 同 kind）；kind 为空渲染全部 */
    public String renderForPrompt(String sessionKind) {
        if (catalogRegistry.entries().isEmpty()) {
            return "(无 workflow 目录配置)";
        }
        return catalogRegistry.entries().stream()
                .filter(e -> !StringUtils.hasText(sessionKind)
                        || ResourceKindFilter.matches(e.kind(), sessionKind))
                .map(this::formatEntry)
                .collect(Collectors.joining("\n"));
    }

    public String renderIntoClassifier(String classifierPrompt, String sessionKind) {
        if (!StringUtils.hasText(classifierPrompt)) {
            return classifierPrompt;
        }
        return classifierPrompt.replace("{{workflow-catalog}}", renderForPrompt(sessionKind));
    }

    public boolean isKnownWorkflow(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return false;
        }
        String id = workflowId.strip();
        if (catalogRegistry.find(id) != null) {
            return true;
        }
        return workflowManagerClient.fetchPublished(id).isPresent();
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
        if (workflowManagerClient.fetchPublished(plan.workflowId()).isEmpty()) {
            return ExecutionPlan.reactFallback("missing definition: " + plan.workflowId());
        }
        return plan;
    }

    public WorkflowManagerClient.WorkflowCatalogEntryDto findEntry(String workflowId) {
        return catalogRegistry.find(workflowId);
    }

    private String formatEntry(WorkflowManagerClient.WorkflowCatalogEntryDto e) {
        String nodes = e.nodes() != null ? String.join(" → ", e.nodes()) : "";
        String examples = e.examples() != null ? String.join("；", e.examples()) : "";
        String desc = StringUtils.hasText(e.description()) ? e.description() : e.displayName();
        return "- **" + e.id() + "** (mode=" + e.mode() + "): " + desc
                + "\n  节点: " + nodes
                + (StringUtils.hasText(examples) ? "\n  示例: " + examples : "");
    }
}
