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
        return requireService().intentDetail(plan);
    }

    /** 根据已写入 step.detail 还原用户向摘要（重连 / 回放） */
    public static String intentAfterSummary(String clippedQuery, String detail) {
        return requireService().intentAfterSummary(clippedQuery, detail);
    }

    /** 有意图路由计划时生成 after（主行展示） */
    public static String intentAfterForPlan(String userQuery, ExecutionPlan plan) {
        return requireService().intentAfterForPlan(userQuery, plan);
    }

    private static IntentLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("IntentLabelService 未 bind");
        }
        return service;
    }
}
