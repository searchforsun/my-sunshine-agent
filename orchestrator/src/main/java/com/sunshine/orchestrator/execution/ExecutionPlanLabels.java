package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.processing.IntentLabels;
import com.sunshine.orchestrator.routing.ExecutionPlan;

/**
 * 意图步骤展示文案（委托 {@link IntentLabels}，配置见 Nacos agent.timeline.intent）
 */
public final class ExecutionPlanLabels {

    private ExecutionPlanLabels() {
    }

    public static String intentDetail(ExecutionPlan plan) {
        return IntentLabels.intentDetail(plan);
    }
}
