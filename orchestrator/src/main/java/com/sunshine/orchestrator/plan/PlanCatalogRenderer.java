package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/** 渲染 Skill / Tool / Workflow 目录供 Planner prompt 注入 */
@Component
@RequiredArgsConstructor
public class PlanCatalogRenderer {

    private final SkillCatalogService skillCatalogService;
    private final ToolCatalogService toolCatalogService;
    private final ToolSetResolver toolSetResolver;
    private final WorkflowCatalog workflowCatalog;

    public String renderIntoPrompt(String template, String tenantId) {
        if (!StringUtils.hasText(template)) {
            return template;
        }
        return template
                .replace("{{skill-catalog}}", renderSkills())
                .replace("{{tool-catalog}}", renderTools(tenantId))
                .replace("{{workflow-catalog}}", workflowCatalog.renderForPrompt());
    }

    private String renderSkills() {
        if (skillCatalogService.indexEntries().isEmpty()) {
            return "(无 skill 目录)";
        }
        return skillCatalogService.indexEntries().stream()
                .filter(SkillCatalogIndexEntry::enabled)
                .map(e -> "- **" + e.id() + "**: " + e.displayName()
                        + " | sandbox=" + e.sandbox()
                        + (StringUtils.hasText(e.description()) ? " — " + e.description() : ""))
                .collect(Collectors.joining("\n"));
    }

    private String renderTools(String tenantId) {
        List<String> toolIds = toolSetResolver.resolvePlanWorkflowTools(tenantId);
        if (toolIds.isEmpty()) {
            return "(无 tool 目录)";
        }
        return toolIds.stream()
                .map(toolCatalogService::find)
                .flatMap(java.util.Optional::stream)
                .map(e -> "- **" + e.id() + "**: " + e.displayName())
                .collect(Collectors.joining("\n"));
    }
}
