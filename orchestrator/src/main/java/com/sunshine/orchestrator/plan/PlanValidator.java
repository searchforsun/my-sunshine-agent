package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.common.workflow.WorkflowNodeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/** Plan JSON 硬约束校验（Planner 业务 + BPMN 路由节点 → Normalizer 拼接 answer） */
@Component
@RequiredArgsConstructor
public class PlanValidator {

    private static final Set<String> PLANNER_TYPES = WorkflowNodeType.plannerTypeIds();
    private static final Set<String> EXEC_TYPES = WorkflowNodeType.planExecTypeIds();

    private final SkillCatalogService skillCatalogService;
    private final ToolCatalogService toolCatalogService;
    private final AgentPromptProperties agentPromptProperties;

    /** Planner 原始输出校验（normalize 前） */
    public PlanValidationIssue validatePlannerOutput(PlanJson plan) {
        if (plan == null || plan.nodes().isEmpty()) {
            return PlanValidationIssue.of(PlanValidationCode.NODES_EMPTY, "nodes 为空");
        }
        AgentPromptProperties.Planner plannerCfg = agentPromptProperties.plannerOrDefault();
        int maxNodes = plannerCfg.getMaxNodes();
        int nodeLimit = maxNodes + plannerCfg.getRoutingNodeBuffer();
        long complexity = PlanNormalizer.countPlannerComplexityNodes(plan);
        if (complexity > maxNodes) {
            return PlanValidationIssue.of(
                    PlanValidationCode.TOO_MANY_NODES,
                    "业务节点数超过上限 " + maxNodes,
                    PlanValidationCode.TOO_MANY_NODES.defaultFixHint().replace("{max}", String.valueOf(maxNodes)));
        }
        if (plan.nodes().size() > nodeLimit) {
            return PlanValidationIssue.of(
                    PlanValidationCode.TOO_MANY_TOTAL_NODES,
                    "节点总数超过上限 " + nodeLimit,
                    PlanValidationCode.TOO_MANY_TOTAL_NODES.defaultFixHint().replace("{max}", String.valueOf(nodeLimit)));
        }
        for (PlanNode node : plan.nodes()) {
            if (!PLANNER_TYPES.contains(node.type())) {
                return PlanValidationIssue.of(
                        PlanValidationCode.ILLEGAL_NODE_TYPE,
                        "Planner 非法节点 type: " + node.type());
            }
            PlanValidationIssue err = validatePlannerNode(node);
            if (err != null) {
                return err;
            }
            if (!StringUtils.hasText(node.displayName())) {
                return PlanValidationIssue.of(
                        PlanValidationCode.MISSING_DISPLAY_NAME,
                        "节点 " + node.id() + " 缺少 displayName");
            }
        }
        for (PlanEdge edge : plan.edges()) {
            if (PlanNormalizer.ANSWER_NODE_ID.equals(edge.from())
                    || PlanNormalizer.ANSWER_NODE_ID.equals(edge.to())) {
                return PlanValidationIssue.of(PlanValidationCode.ANSWER_IN_EDGES, "Planner edges 勿指向 answer");
            }
        }
        PlanJson preview = PlanNormalizer.normalize(plan);
        PlanValidationIssue parallelErr = PlanExecutionSchedule.validateParallelTopology(preview);
        if (parallelErr != null) {
            return parallelErr;
        }
        PlanValidationIssue exclusiveErr = PlanExecutionSchedule.validateExclusiveTopology(preview);
        if (exclusiveErr != null) {
            return exclusiveErr;
        }
        PlanValidationIssue loopErr = PlanExecutionSchedule.validateLoopTopology(preview);
        if (loopErr != null) {
            return loopErr;
        }
        return null;
    }

    /** normalize + enrich 后校验（执行前） */
    public PlanValidationIssue validate(PlanJson plan) {
        if (plan.nodes().isEmpty()) {
            return PlanValidationIssue.of(PlanValidationCode.NODES_EMPTY, "nodes 为空");
        }
        AgentPromptProperties.Planner plannerCfg = agentPromptProperties.plannerOrDefault();
        int maxNodes = plannerCfg.getMaxNodes();
        int nodeLimit = maxNodes + plannerCfg.getRoutingNodeBuffer();
        long businessNodes = plan.nodes().stream()
                .filter(n -> !"answer".equals(n.type()))
                .count();
        if (businessNodes > nodeLimit) {
            return PlanValidationIssue.of(
                    PlanValidationCode.TOO_MANY_TOTAL_NODES,
                    "节点总数超过上限 " + nodeLimit,
                    PlanValidationCode.TOO_MANY_TOTAL_NODES.defaultFixHint().replace("{max}", String.valueOf(nodeLimit)));
        }
        boolean hasAnswer = false;
        for (PlanNode node : plan.nodes()) {
            if (!EXEC_TYPES.contains(node.type())) {
                return PlanValidationIssue.of(
                        PlanValidationCode.ILLEGAL_NODE_TYPE,
                        "非法节点类型: " + node.type());
            }
            if ("answer".equals(node.type())) {
                if (!PlanNormalizer.ANSWER_NODE_ID.equals(node.id())) {
                    return PlanValidationIssue.of(
                            PlanValidationCode.VALIDATION_FAILED,
                            "answer 节点须为引擎固定 id: " + PlanNormalizer.ANSWER_NODE_ID);
                }
                hasAnswer = true;
                continue;
            }
            PlanValidationIssue err = validateBusinessNode(node);
            if (err != null) {
                return err;
            }
            if (!StringUtils.hasText(node.displayName())) {
                return PlanValidationIssue.of(
                        PlanValidationCode.MISSING_DISPLAY_NAME,
                        "节点 " + node.id() + " 缺少 displayName");
            }
        }
        if (!hasAnswer) {
            return PlanValidationIssue.of(
                    PlanValidationCode.VALIDATION_FAILED, "Plan 须包含引擎固定 answer 节点");
        }
        PlanValidationIssue parallelErr = PlanExecutionSchedule.validateParallelTopology(plan);
        if (parallelErr != null) {
            return parallelErr;
        }
        PlanValidationIssue exclusiveErr = PlanExecutionSchedule.validateExclusiveTopology(plan);
        if (exclusiveErr != null) {
            return exclusiveErr;
        }
        PlanValidationIssue loopErr = PlanExecutionSchedule.validateLoopTopology(plan);
        if (loopErr != null) {
            return loopErr;
        }
        return null;
    }

    private PlanValidationIssue validatePlannerNode(PlanNode node) {
        if (WorkflowNodeType.plannerBusinessTypeIds().contains(node.type())) {
            return validateBusinessNode(node);
        }
        if ("parallel-gateway".equals(node.type()) || "join".equals(node.type())) {
            return null;
        }
        if ("exclusive-gateway".equals(node.type())) {
            return null;
        }
        if ("loop".equals(node.type())) {
            return null;
        }
        return PlanValidationIssue.of(
                PlanValidationCode.ILLEGAL_NODE_TYPE,
                "Planner 非法节点 type: " + node.type());
    }

    private PlanValidationIssue validateBusinessNode(PlanNode node) {
        if ("tool".equals(node.type())) {
            String tool = readParamString(node, "tool");
            if (!StringUtils.hasText(tool) || toolCatalogService.find(tool.strip()).isEmpty()) {
                return PlanValidationIssue.of(
                        PlanValidationCode.UNKNOWN_TOOL,
                        "未知工具: " + tool);
            }
        }
        if ("agent".equals(node.type())) {
            String skillId = readParamString(node, "skill");
            if (StringUtils.hasText(skillId) && skillCatalogService.findIndex(skillId.strip()).isEmpty()) {
                return PlanValidationIssue.of(
                        PlanValidationCode.VALIDATION_FAILED,
                        "未知 skill: " + skillId);
            }
            if (!StringUtils.hasText(readParamString(node, "context"))) {
                return PlanValidationIssue.of(
                        PlanValidationCode.AGENT_CONTEXT,
                        "agent 节点 " + node.id() + " 缺少 params.context");
            }
            if (!StringUtils.hasText(readParamString(node, "query"))) {
                return PlanValidationIssue.of(
                        PlanValidationCode.AGENT_CONTEXT,
                        "agent 节点 " + node.id() + " 缺少 params.query");
            }
        }
        return null;
    }

    private static String readParamString(PlanNode node, String key) {
        if (node.params() == null) {
            return null;
        }
        Object v = node.params().get(key);
        return v != null ? v.toString() : null;
    }
}
