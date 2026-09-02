package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.common.workflow.WorkflowNodeType;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/** 动态 Plan 的 Timeline plan 步 */
public final class PlanTimeline {

    private PlanTimeline() {
    }

    /** 静态 Workflow plan 步 — 含 planId + 节点链（供 3.12.4 跳转） */
    public static List<StreamToken> planStep(ProcessingTimelineSession session, PlanJson plan, String persistedPlanId) {
        String chain = planChainSummary(plan);
        String detail = formatPlanDetail(persistedPlanId, chain);
        long startedAt = System.currentTimeMillis();
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending("plan", "plan");
            session.startAt("plan", "plan", startedAt);
            session.completePlanAt(chain, detail, System.currentTimeMillis());
        });
    }

    /** Planner 失败或降级 react 前 — 仍展示 plan 步说明 */
    public static List<StreamToken> planFallbackStep(ProcessingTimelineSession session, String summaryAfter) {
        long startedAt = System.currentTimeMillis();
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending("plan", "plan");
            session.startAt("plan", "plan", startedAt);
            session.completeAt("plan", summaryAfter, System.currentTimeMillis());
        });
    }

    /** Timeline plan 步 detail：planId + 节点链（供 3.12.4 跳转） */
    public static String formatPlanDetail(String persistedPlanId, String chainSummary) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(persistedPlanId)) {
            sb.append("planId=").append(persistedPlanId.strip());
        }
        if (StringUtils.hasText(chainSummary)) {
            if (!sb.isEmpty()) {
                sb.append("|");
            }
            sb.append("chain=").append(chainSummary);
        }
        return !sb.isEmpty() ? sb.toString() : (chainSummary != null ? chainSummary : "");
    }

    public static String planChainSummary(PlanJson plan) {
        return PlanLinearizer.linearOrder(plan).stream()
                .map(plan.nodesById()::get)
                .filter(node -> node != null && WorkflowNodeType.isPlanChainNode(node.type()))
                .map(PlanTimeline::nodeLabel)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" → "));
    }

    private static String nodeLabel(PlanNode node) {
        if (StringUtils.hasText(node.displayName())) {
            return node.displayName().strip();
        }
        return WorkflowNodeLabels.displayName(node.id(), node.type());
    }
}
