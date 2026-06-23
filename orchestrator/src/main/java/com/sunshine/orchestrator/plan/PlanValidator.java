package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/** Plan JSON 硬约束校验（MVP 线性 DAG） */
@Component
@RequiredArgsConstructor
public class PlanValidator {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "start", "rag", "tool", "llm", "agent", "answer");

    private final SkillCatalogService skillCatalogService;
    private final ToolCatalogService toolCatalogService;
    private final AgentPromptProperties agentPromptProperties;

    /** @return 校验失败原因；空表示通过 */
    public String validate(PlanJson plan) {
        if (plan.nodes().isEmpty()) {
            return "nodes 为空";
        }
        int maxNodes = agentPromptProperties.plannerOrDefault().getMaxNodes();
        long businessNodes = plan.nodes().stream()
                .filter(n -> !"start".equals(n.type()) && !"answer".equals(n.type()))
                .count();
        if (businessNodes > maxNodes) {
            return "节点数超过上限 " + maxNodes;
        }
        boolean hasAnswer = false;
        for (PlanNode node : plan.nodes()) {
            if (!ALLOWED_TYPES.contains(node.type())) {
                return "非法节点类型: " + node.type();
            }
            if ("llm".equals(node.type())) {
                return "Plan 禁止 llm 节点，请用 answer 节点承载 params.prompt 与流式输出";
            }
            if ("answer".equals(node.type())) {
                hasAnswer = true;
            }
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
            if ("answer".equals(node.type()) && !StringUtils.hasText(node.params().get("prompt"))) {
                return "answer 节点 " + node.id() + " 缺少 params.prompt";
            }
            if (needsDisplayName(node.type()) && !StringUtils.hasText(node.displayName())) {
                return "节点 " + node.id() + " 缺少 displayName";
            }
        }
        if (!hasAnswer) {
            return "Plan 须包含 answer 节点（承载综合分析 + 流式回答）";
        }
        return null;
    }

    private static boolean needsDisplayName(String type) {
        return !"start".equals(type);
    }
}
