package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;

/**
 * 意图步骤展示文案
 */
public final class ExecutionPlanLabels {

    private ExecutionPlanLabels() {
    }

    public static String intentDetail(ExecutionPlan plan) {
        if (plan == null) {
            return "简单对话";
        }
        return switch (plan.mode()) {
            case SIMPLE_LLM -> "简单对话";
            case WORKFLOW -> "工作流 · " + (plan.workflowId() != null ? plan.workflowId() : "unknown");
            case REACT -> "自主 Agent";
        };
    }
}
