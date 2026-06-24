package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/** Plan JSON 硬约束校验（Planner 纯业务节点 → Normalizer 拼接 answer） */
@Component
@RequiredArgsConstructor
public class PlanValidator {

    private static final Set<String> PLANNER_TYPES = Set.of("rag", "tool", "agent");
    private static final Set<String> EXEC_TYPES = Set.of("rag", "tool", "agent", "answer");

    private final SkillCatalogService skillCatalogService;
    private final ToolCatalogService toolCatalogService;
    private final AgentPromptProperties agentPromptProperties;

    /** Planner 原始输出校验（normalize 前） */
    public String validatePlannerOutput(PlanJson plan) {
        if (plan == null || plan.nodes().isEmpty()) {
            return "nodes 为空";
        }
        int maxNodes = agentPromptProperties.plannerOrDefault().getMaxNodes();
        if (plan.nodes().size() > maxNodes) {
            return "节点数超过上限 " + maxNodes;
        }
        for (PlanNode node : plan.nodes()) {
            if (!PLANNER_TYPES.contains(node.type())) {
                return "Planner 非法节点 type: " + node.type();
            }
            String err = validateBusinessNode(node);
            if (err != null) {
                return err;
            }
            if (!StringUtils.hasText(node.displayName())) {
                return "节点 " + node.id() + " 缺少 displayName";
            }
        }
        for (PlanEdge edge : plan.edges()) {
            if (PlanNormalizer.ANSWER_NODE_ID.equals(edge.from())
                    || PlanNormalizer.ANSWER_NODE_ID.equals(edge.to())) {
                return "Planner edges 勿指向 answer";
            }
        }
        return null;
    }

    /** normalize + enrich 后校验（执行前） */
    public String validate(PlanJson plan) {
        if (plan.nodes().isEmpty()) {
            return "nodes 为空";
        }
        int maxNodes = agentPromptProperties.plannerOrDefault().getMaxNodes();
        long businessNodes = plan.nodes().stream()
                .filter(n -> !"answer".equals(n.type()))
                .count();
        if (businessNodes > maxNodes) {
            return "节点数超过上限 " + maxNodes;
        }
        boolean hasAnswer = false;
        for (PlanNode node : plan.nodes()) {
            if (!EXEC_TYPES.contains(node.type())) {
                return "非法节点类型: " + node.type();
            }
            if ("answer".equals(node.type())) {
                if (!PlanNormalizer.ANSWER_NODE_ID.equals(node.id())) {
                    return "answer 节点须为引擎固定 id: " + PlanNormalizer.ANSWER_NODE_ID;
                }
                hasAnswer = true;
                continue;
            }
            String err = validateBusinessNode(node);
            if (err != null) {
                return err;
            }
            if (!StringUtils.hasText(node.displayName())) {
                return "节点 " + node.id() + " 缺少 displayName";
            }
        }
        if (!hasAnswer) {
            return "Plan 须包含引擎固定 answer 节点";
        }
        return null;
    }

    private String validateBusinessNode(PlanNode node) {
        if ("tool".equals(node.type())) {
            String tool = node.params().get("tool");
            if (!StringUtils.hasText(tool) || toolCatalogService.find(tool.strip()).isEmpty()) {
                return "未知工具: " + tool;
            }
        }
        if ("agent".equals(node.type())) {
            String skillId = node.params().get("skill");
            if (StringUtils.hasText(skillId) && skillCatalogService.findIndex(skillId.strip()).isEmpty()) {
                return "未知 skill: " + skillId;
            }
            if (!StringUtils.hasText(node.params().get("context"))) {
                return "agent 节点 " + node.id() + " 缺少 params.context";
            }
            if (!StringUtils.hasText(node.params().get("query"))) {
                return "agent 节点 " + node.id() + " 缺少 params.query";
            }
        }
        return null;
    }
}
