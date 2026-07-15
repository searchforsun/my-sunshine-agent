package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.orchestrator.execution.WorkflowNodeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 为 Planner 节点补全 displayName（避免 Timeline / Plan 图出现 n1/n2） */
@Component
@RequiredArgsConstructor
public class PlanDisplayNameEnricher {

    private final ToolCatalogService toolCatalogService;
    private final SkillCatalogService skillCatalogService;
    private final WorkflowNodeLabelService workflowNodeLabelService;

    public PlanJson enrich(PlanJson plan) {
        List<PlanNode> nodes = new ArrayList<>();
        for (PlanNode node : plan.nodes()) {
            nodes.add(enrichNode(node));
        }
        return new PlanJson(plan.planId(), plan.reason(), List.copyOf(nodes), plan.edges(), plan.layout());
    }

    private PlanNode enrichNode(PlanNode node) {
        if (StringUtils.hasText(node.displayName())) {
            return node;
        }
        String name = resolveDisplayName(node);
        return new PlanNode(node.id(), node.type(), node.params(), name, node.parentId());
    }

    private String resolveDisplayName(PlanNode node) {
        String type = node.type() != null ? node.type() : "";
        if (WorkflowNodeType.TOOL.matches(type)) {
            String tool = node.params().get("tool");
            if (StringUtils.hasText(tool)) {
                return toolCatalogService.displayName(tool.strip());
            }
            return workflowNodeLabelService.typeLabel(type);
        }
        if (WorkflowNodeType.AGENT.matches(type)) {
            String skill = node.params().get("skill");
            if (StringUtils.hasText(skill)) {
                return skillCatalogService.findIndex(skill.strip())
                        .map(SkillCatalogIndexEntry::displayName)
                        .filter(StringUtils::hasText)
                        .orElse(workflowNodeLabelService.subAgentDefaultLabel());
            }
            return workflowNodeLabelService.typeLabel(type);
        }
        if (WorkflowNodeType.of(type).isPresent()) {
            return workflowNodeLabelService.typeLabel(type);
        }
        return WorkflowNodeLabels.displayName(node.id(), node.type());
    }
}
