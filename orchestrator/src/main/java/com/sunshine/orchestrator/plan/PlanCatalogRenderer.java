package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.stream.Collectors;

/** 渲染 Skill / Tool / Workflow 目录供 Planner prompt 注入 */
@Component
@RequiredArgsConstructor
public class PlanCatalogRenderer {

    private final SkillCatalogService skillCatalogService;
    private final ToolCatalogService toolCatalogService;
    private final WorkflowCatalog workflowCatalog;

    public String renderIntoPrompt(String template) {
        if (!StringUtils.hasText(template)) {
            return template;
        }
        return template
                .replace("{{skill-catalog}}", renderSkills())
                .replace("{{tool-catalog}}", renderTools())
                .replace("{{workflow-catalog}}", workflowCatalog.renderForPrompt());
    }

    private String renderSkills() {
        if (skillCatalogService.indexEntries().isEmpty()) {
            return "(无 skill 目录)";
        }
        return skillCatalogService.indexEntries().stream()
                .filter(SkillCatalogIndexEntry::enabled)
                .map(e -> "- **" + e.id() + "**: " + e.displayName()
                        + (StringUtils.hasText(e.description()) ? " — " + e.description() : ""))
                .collect(Collectors.joining("\n"));
    }

    private String renderTools() {
        if (toolCatalogService.allEntries().isEmpty()) {
            return "(无 tool 目录)";
        }
        return toolCatalogService.allEntries().stream()
                .map(e -> "- **" + e.id() + "**: " + e.displayName())
                .collect(Collectors.joining("\n"));
    }
}
