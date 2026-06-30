package com.sunshine.orchestrator.processing;

/** 步骤 before/active/after 文案（Nacos StepSummarizer + node displayName） */
final class TimelineSessionSummaries {

    private final TimelineSessionState state;

    TimelineSessionSummaries(TimelineSessionState state) {
        this.state = state;
    }

    String resolveBefore(String stepId) {
        if (stepId != null && stepId.startsWith("node-")) {
            String name = state.stepDisplayNames.get(stepId);
            if (name != null && !name.isBlank()) {
                return "准备" + name;
            }
        }
        return state.userQuery != null
                ? StepSummarizer.before(stepId, state.userQuery, state.lastCompletedToolDisplayName)
                : StepSummarizer.beforeFallback(stepId);
    }

    String resolveActive(String stepId) {
        if (stepId != null && stepId.startsWith("node-")) {
            String name = state.stepDisplayNames.get(stepId);
            if (name != null && !name.isBlank()) {
                return "正在" + name;
            }
        }
        return state.userQuery != null
                ? StepSummarizer.active(stepId, state.userQuery, state.lastCompletedToolDisplayName)
                : StepSummarizer.activeFallback(stepId);
    }

    String resolveAfter(String stepId, String detail, StepMetadata metadata) {
        if (state.userQuery != null && metadata != null && !metadata.isEmpty()
                && (ToolStepIds.isRagStep(stepId) || isWorkflowRagNode(stepId))) {
            return StepSummarizer.afterRag(state.userQuery, detail, metadata);
        }
        return state.userQuery != null
                ? StepSummarizer.after(stepId, state.userQuery, detail, state.lastCompletedToolDisplayName)
                : StepSummarizer.afterFallback(stepId, detail);
    }

    static boolean isWorkflowRagNode(String stepId) {
        return stepId != null && stepId.startsWith("node-rag");
    }
}
