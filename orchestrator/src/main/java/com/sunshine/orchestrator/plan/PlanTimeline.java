package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/** 动态 Plan 的 Timeline plan 步 */
public final class PlanTimeline {

    private PlanTimeline() {
    }

    public static List<StreamToken> planStep(ProcessingTimelineSession session, PlanJson plan, String persistedPlanId) {
        return planStep(session, plan, persistedPlanId, 0);
    }

    public static List<StreamToken> planStep(
            ProcessingTimelineSession session,
            PlanJson plan,
            String persistedPlanId,
            int replanCount) {
        String chain = planChainSummary(plan);
        String detail = formatPlanDetail(persistedPlanId, chain, replanCount);
        long startedAt = System.currentTimeMillis();
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending("plan", "plan");
            session.startAt("plan", "plan", startedAt);
            String after = replanCount > 0
                    ? "规划经 " + replanCount + " 次修正后开始执行"
                    : chain;
            session.completeAt("plan", after, detail, System.currentTimeMillis());
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

    /** Plan 校验未通过、降级 ReAct — 不下发 planId，前端不展示 DAG */
    public static List<StreamToken> planRejectedStep(ProcessingTimelineSession session, String reason) {
        String after = "Plan 校验未通过，降级为 ReAct";
        if (StringUtils.hasText(reason)) {
            after = after + "（" + reason.strip() + "）";
        }
        return planFallbackStep(session, after);
    }

    public static List<StreamToken> planStep(ProcessingTimelineSession session, PlanJson plan) {
        return planStep(session, plan, null);
    }

    /** Timeline plan 步 detail：planId + 节点链（供 3.12.4 跳转） */
    public static String formatPlanDetail(String persistedPlanId, String chainSummary) {
        return formatPlanDetail(persistedPlanId, chainSummary, 0);
    }

    public static String formatPlanDetail(String persistedPlanId, String chainSummary, int replanCount) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(persistedPlanId)) {
            sb.append("planId=").append(persistedPlanId.strip());
        }
        if (replanCount > 0) {
            if (!sb.isEmpty()) {
                sb.append("|");
            }
            sb.append("replanCount=").append(replanCount);
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
                .filter(node -> node != null && !"start".equals(node.type()) && !"answer".equals(node.type()))
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
