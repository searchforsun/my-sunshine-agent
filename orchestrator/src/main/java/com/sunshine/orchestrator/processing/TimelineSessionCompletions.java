package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ToolResultSummarizer;
import com.sunshine.orchestrator.routing.ExecutionPlan;

/** intent / plan / skill / RAG 等阶段完成逻辑 */
final class TimelineSessionCompletions {

    private final TimelineSessionState state;
    private final TimelineSessionEmitter emitter;
    private final TimelineSessionSummaries summaries;

    TimelineSessionCompletions(
            TimelineSessionState state,
            TimelineSessionEmitter emitter,
            TimelineSessionSummaries summaries) {
        this.state = state;
        this.emitter = emitter;
        this.summaries = summaries;
    }

    void completeIntent(ExecutionPlan plan) {
        completeIntent(plan, com.sunshine.orchestrator.rewrite.QueryRewriteTrace.intentOutcome(state.traceMessageId).orElse(null));
    }

    void completeIntent(ExecutionPlan plan, com.sunshine.orchestrator.rewrite.QueryRewriteOutcome intentRewrite) {
        String after = IntentLabels.intentAfterForPlan(state.userQuery, plan);
        StepMetadata metadata = StepMetadata.mergeRouting(
                StepMetadata.fromRewrite(intentRewrite), plan);
        String detail = intentRewrite != null ? intentRewrite.timelineDetail() : null;
        emitter.applyAt("intent", null, EventKind.COMPLETE, after, detail, metadata, System.currentTimeMillis());
        if ("intent".equals(state.activeStepId)) {
            state.activeStepId = null;
        }
    }

    void completePlanAt(String after, String detail, long endedAt) {
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome plannerRewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.plannerOutcome(state.traceMessageId).orElse(null);
        StepMetadata metadata = StepMetadata.fromRewrite(plannerRewrite);
        emitter.applyAt("plan", null, EventKind.COMPLETE, after, detail, metadata, endedAt);
        if ("plan".equals(state.activeStepId)) {
            state.activeStepId = null;
        }
    }

    void beginPlanAwaitingApproval(String detail, StepMetadata metadata) {
        long ts = System.currentTimeMillis();
        emitter.apply("plan", "plan", EventKind.PENDING, summaries.resolveBefore("plan"), null);
        startAt("plan", "plan", ts);
        emitter.applyAt("plan", null, EventKind.PROGRESS, PlanApprovalLabels.awaiting(), detail, metadata, ts);
        state.activeStepId = "plan";
    }

    void updatePlanApproval(StepMetadata metadata, String activeSummary) {
        String summary = activeSummary != null && !activeSummary.isBlank()
                ? activeSummary
                : PlanApprovalLabels.awaiting();
        emitter.applyAt("plan", null, EventKind.PROGRESS, summary, null, metadata, System.currentTimeMillis());
    }

    void completeSkillLoad(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return;
        }
        long ts = System.currentTimeMillis();
        emitter.apply("skill", "skill", EventKind.PENDING, summaries.resolveBefore("skill"), null);
        startAt("skill", "skill", ts);
        String after = SkillLoadLabels.after(skillId.strip());
        StepMetadata metadata = StepMetadata.fromSkillLoad(skillId.strip());
        emitter.applyAt("skill", "skill", EventKind.COMPLETE, after, null, metadata, ts);
        if ("skill".equals(state.activeStepId)) {
            state.activeStepId = null;
        }
    }

    void completeAt(String stepId, String detail, long endedAt) {
        completeAt(stepId, detail, detail, endedAt);
    }

    void completeAt(String stepId, String summaryLine, String expandDetail, long endedAt) {
        StepMetadata metadata = null;
        if (summaryLine != null && (ToolStepIds.isRagStep(stepId) || TimelineSessionSummaries.isWorkflowRagNode(stepId))) {
            String ragInput = summaryLine;
            if (ToolStepIds.isRagStep(stepId) && containsRawRagBody(summaryLine)) {
                ragInput = ToolResultSummarizer.summarize("search_knowledge", summaryLine);
            }
            metadata = StepMetadata.fromRagToolOutput(summaryLine, ragInput);
        }
        String after = summaries.resolveAfter(stepId, summaryLine, metadata);
        Integer baseline = state.ragRewriteBaselineByStep.remove(stepId);
        int rewriteFromIndex = baseline != null ? baseline : 0;
        String rewriteDetail = com.sunshine.orchestrator.rewrite.QueryRewriteTrace
                .combinedRagTimelineDetailSince(state.traceMessageId, rewriteFromIndex);
        String storedDetail;
        if (ToolStepIds.isRagStep(stepId) || TimelineSessionSummaries.isWorkflowRagNode(stepId)) {
            metadata = mergeRagRewriteMetadataSince(metadata, rewriteFromIndex);
            storedDetail = resolveRagStoredDetail(stepId, summaryLine, rewriteDetail);
            if (rewriteDetail != null && !rewriteDetail.isBlank()) {
                metadata = StepMetadata.withRagExpandLayout(metadata);
            }
        } else {
            storedDetail = expandDetail;
        }
        emitter.applyAt(stepId, null, EventKind.COMPLETE, after, storedDetail, metadata, endedAt);
        if (stepId.equals(state.activeStepId)) {
            state.activeStepId = null;
        }
    }

    void startAt(String stepId, String phase, long startedAt) {
        if (stepId.equals(state.activeStepId)) {
            ProcessingStep current = state.aggregator.get(stepId).orElse(null);
            if (current != null && "running".equals(current.lifecycle())) {
                return;
            }
        } else {
            completeRunningActive(startedAt);
        }
        state.activeStepId = stepId;
        captureRagRewriteBaseline(stepId);
        emitter.applyAt(stepId, phase, EventKind.START, summaries.resolveActive(stepId), null, startedAt);
    }

    private void completeRunningActive(long endedAt) {
        if (state.activeStepId == null) {
            return;
        }
        if (ThinkStepIds.isThinkStep(state.activeStepId) || "generate".equals(state.activeStepId)) {
            return;
        }
        state.aggregator.get(state.activeStepId).ifPresent(step -> {
            if ("running".equals(step.lifecycle())) {
                completeAt(state.activeStepId, step.detail(), endedAt);
            }
        });
    }

    private void captureRagRewriteBaseline(String stepId) {
        if (state.traceMessageId == null || stepId == null) {
            return;
        }
        if (ToolStepIds.isRagStep(stepId) || TimelineSessionSummaries.isWorkflowRagNode(stepId)) {
            state.ragRewriteBaselineByStep.put(stepId,
                    com.sunshine.orchestrator.rewrite.QueryRewriteTrace.size(state.traceMessageId));
        }
    }

    private StepMetadata mergeRagRewriteMetadataSince(StepMetadata metadata, int fromIndex) {
        if (state.traceMessageId == null) {
            return metadata;
        }
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome ragRewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.latestSince(state.traceMessageId, "rag", fromIndex)
                        .orElse(null);
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome hydeRewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.latestSince(state.traceMessageId, "hyde", fromIndex)
                        .orElse(null);
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome emptyRewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.latestSince(state.traceMessageId, "empty-recall", fromIndex)
                        .orElse(null);
        StepMetadata merged = StepMetadata.mergeRewrite(metadata, ragRewrite);
        merged = StepMetadata.mergeRewrite(merged, hydeRewrite);
        return StepMetadata.mergeRewrite(merged, emptyRewrite);
    }

    private static boolean containsRawRagBody(String detail) {
        return detail.contains("【")
                || detail.contains("知识库检索结果（共")
                || detail.contains("片段");
    }

    private static String resolveRagStoredDetail(String stepId, String summaryLine, String rewriteDetail) {
        if (rewriteDetail != null && !rewriteDetail.isBlank()) {
            return rewriteDetail.strip();
        }
        if (TimelineSessionSummaries.isWorkflowRagNode(stepId) && summaryLine != null && !summaryLine.isBlank()) {
            return summaryLine.strip();
        }
        return rewriteDetail;
    }
}
