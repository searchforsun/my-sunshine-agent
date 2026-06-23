package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
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

    public PlanJson enrich(PlanJson plan) {
        List<PlanNode> nodes = new ArrayList<>();
        for (PlanNode node : plan.nodes()) {
            nodes.add(enrichNode(node));
        }
        return new PlanJson(plan.planId(), plan.reason(), List.copyOf(nodes), plan.edges());
    }

    private PlanNode enrichNode(PlanNode node) {
        if (StringUtils.hasText(node.displayName())) {
            return node;
        }
        String name = resolveDisplayName(node);
        return new PlanNode(node.id(), node.type(), node.params(), name);
    }

    private String resolveDisplayName(PlanNode node) {
        String type = node.type() != null ? node.type() : "";
        return switch (type) {
            case "rag" -> "检索知识库";
            case "llm" -> "综合分析";
            case "answer" -> "生成回答";
            case "tool" -> {
                String tool = node.params().get("tool");
                if (StringUtils.hasText(tool)) {
                    yield toolCatalogService.displayName(tool.strip());
                }
                yield "调用工具";
            }
            case "agent" -> {
                String skill = node.params().get("skill");
                if (StringUtils.hasText(skill)) {
                    yield skillCatalogService.findIndex(skill.strip())
                            .map(SkillCatalogIndexEntry::displayName)
                            .filter(StringUtils::hasText)
                            .orElse("子 Agent 分析");
                }
                yield "智能体分析";
            }
            default -> WorkflowNodeLabels.displayName(node.id(), node.type());
        };
    }
}
