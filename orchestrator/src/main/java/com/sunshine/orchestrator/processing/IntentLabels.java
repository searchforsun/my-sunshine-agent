package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.routing.ExecutionPlan;

/**
 * 意图步骤展示文案静态入口（由 {@link IntentLabelService} 绑定 Nacos 配置）
 */
public final class IntentLabels {

    private static volatile IntentLabelService service;

    private IntentLabels() {
    }

    public static void bind(IntentLabelService labelService) {
        service = labelService;
    }

    public static String intentDetail(ExecutionPlan plan) {
        if (service != null) {
            return service.intentDetail(plan);
        }
        return fallbackIntentDetail(plan);
    }

    /** 根据已写入 step.detail 还原用户向摘要（重连 / 回放） */
    public static String intentAfterSummary(String clippedQuery, String detail) {
        if (service != null) {
            return service.intentAfterSummary(clippedQuery, detail);
        }
        return fallbackAfterSummary(clippedQuery, detail);
    }

    /** 有意图路由计划时生成 after（主行展示） */
    public static String intentAfterForPlan(String userQuery, ExecutionPlan plan) {
        if (service != null) {
            return service.intentAfterForPlan(userQuery, plan);
        }
        String q = StepSummarizer.clipQuery(userQuery);
        if (plan == null) {
            return fallbackAfterSummary(q, null);
        }
        return fallbackAfterSummary(q, fallbackIntentDetail(plan));
    }

    private static String fallbackIntentDetail(ExecutionPlan plan) {
        if (plan == null) {
            return "简单对话";
        }
        return switch (plan.mode()) {
            case SIMPLE_LLM -> "简单对话";
            case REACT -> "自主智能体";
            case PLAN_WORKFLOW -> "动态规划";
            case WORKFLOW -> plan.workflowId() != null ? plan.workflowId() : "工作流";
        };
    }

    private static String fallbackAfterSummary(String q, String detail) {
        if (detail == null || detail.isBlank()) {
            return "已完成对" + q + "的意图判断";
        }
        return q + "将按「" + detail + "」处理";
    }
}
