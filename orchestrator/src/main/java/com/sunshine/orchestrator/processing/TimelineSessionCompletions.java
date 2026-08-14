package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteScenario;
import com.sunshine.orchestrator.rewrite.QueryRewriteTrace;
import com.sunshine.orchestrator.routing.ExecutionPlan;

import java.util.List;

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
        emitter.applyAt(TimelineStepId.INTENT.id(), null, EventKind.COMPLETE, after, detail, metadata, System.currentTimeMillis());
        if (TimelineStepId.INTENT.matches(state.activeStepId)) {
            state.activeStepId = null;
        }
    }

    void completePlanAt(String after, String detail, long endedAt) {
        com.sunshine.orchestrator.rewrite.QueryRewriteOutcome rewrite =
                com.sunshine.orchestrator.rewrite.QueryRewriteTrace.intentOutcome(state.traceMessageId).orElse(null);
        StepMetadata metadata = StepMetadata.fromRewrite(rewrite);
        emitter.applyAt(TimelineStepId.PLAN.id(), null, EventKind.COMPLETE, after, detail, metadata, endedAt);
        if (TimelineStepId.PLAN.matches(state.activeStepId)) {
            state.activeStepId = null;
        }
    }

    void completeSkillLoad(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return;
        }
        long ts = System.currentTimeMillis();
        emitter.apply(TimelineStepId.SKILL.id(), TimelineStepId.SKILL.phase(), EventKind.PENDING,
                summaries.resolveBefore(TimelineStepId.SKILL.id()), null);
        startAt(TimelineStepId.SKILL.id(), TimelineStepId.SKILL.phase(), ts);
        String after = SkillLoadLabels.after(skillId.strip());
        StepMetadata metadata = StepMetadata.fromSkillLoad(skillId.strip());
        emitter.applyAt(TimelineStepId.SKILL.id(), TimelineStepId.SKILL.phase(), EventKind.COMPLETE, after, null, metadata, ts);
        if (TimelineStepId.SKILL.matches(state.activeStepId)) {
            state.activeStepId = null;
        }
    }

    void updateTaskBoard(String stepId, String phase, String activeSummary, StepMetadata metadata) {
        long ts = System.currentTimeMillis();
        if (!emitter.hasStep(stepId)) {
            long anchorStart = taskBoardAnchorStart(ts);
            emitter.apply(stepId, phase, EventKind.PENDING, TaskBoardStepLabels.before(), null);
            startAt(stepId, phase, anchorStart);
        }
        emitter.applyAt(stepId, phase, EventKind.PROGRESS, activeSummary, null, metadata, ts);
    }

    /** tasks 步首建锚定在刚结束的 think 之后，不随 manage_tasks 实际调用时刻漂移 */
    private long taskBoardAnchorStart(long fallback) {
        if (state.lastCompletedThinkEndedAt <= 0) {
            return fallback;
        }
        long anchor = state.lastCompletedThinkEndedAt + 1;
        long minToolAfterThink = emitter.snapshot().stream()
                .filter(s -> ToolStepIds.isToolStep(s.id()))
                .map(ProcessingStep::startedAt)
                .filter(started -> started != null && started >= state.lastCompletedThinkEndedAt)
                .min(Long::compare)
                .orElse(Long.MAX_VALUE);
        if (minToolAfterThink != Long.MAX_VALUE && anchor >= minToolAfterThink) {
            anchor = Math.max(state.lastCompletedThinkEndedAt, minToolAfterThink - 1);
        }
        return anchor;
    }

    void completeTaskBoard(String after, StepMetadata metadata) {
        long ts = System.currentTimeMillis();
        String stepId = TimelineStepId.TASKS.id();
        emitter.applyAt(stepId, TimelineStepId.TASKS.phase(), EventKind.COMPLETE, after, null, metadata, ts);
        if (TimelineStepId.TASKS.matches(state.activeStepId)) {
            state.activeStepId = null;
        }
    }

    void completeAt(String stepId, String detail, long endedAt) {
        completeAt(stepId, detail, detail, endedAt);
    }

    void completeAt(String stepId, String summaryLine, String expandDetail, long endedAt) {
        completeAt(stepId, summaryLine, expandDetail, null, endedAt);
    }

    void completeAt(String stepId, String summaryLine, String expandDetail, StepMetadata extraMetadata, long endedAt) {
        StepMetadata metadata = extraMetadata;
        if (summaryLine != null && (ToolStepIds.isRagStep(stepId) || TimelineSessionSummaries.isWorkflowRagNode(stepId))) {
            String ragInput = summaryLine;
            if (ToolStepIds.isRagStep(stepId) && containsRawRagBody(summaryLine)) {
                ragInput = StepLabels.summarizeOutput("search_knowledge", summaryLine);
            }
            StepMetadata ragMeta = StepMetadata.fromRagToolOutput(summaryLine, ragInput);
            metadata = StepMetadata.merge(metadata, ragMeta);
        }
        String after = summaries.resolveAfter(stepId, summaryLine, metadata);
        Integer baseline = state.ragRewriteBaselineByStep.remove(stepId);
        java.util.Optional<QueryRewriteTrace.RagSpan> ragSpanOpt = QueryRewriteTrace.ragSpan(stepId);
        int rewriteFromIndex = ragSpanOpt.map(QueryRewriteTrace.RagSpan::startIndex)
                .orElse(baseline != null ? baseline : 0);
        int rewriteToIndex = ragSpanOpt.map(QueryRewriteTrace.RagSpan::endIndex)
                .orElse(state.traceMessageId != null
                        ? QueryRewriteTrace.size(state.traceMessageId)
                        : rewriteFromIndex);
        String rewriteDetail = QueryRewriteTrace.combinedRagTimelineDetailForStep(state.traceMessageId, stepId);
        if (rewriteDetail == null || rewriteDetail.isBlank()) {
            rewriteDetail = QueryRewriteTrace
                    .combinedRagTimelineDetailBetween(state.traceMessageId, rewriteFromIndex, rewriteToIndex);
        }
        QueryRewriteTrace.clearRagSpan(stepId);
        String storedDetail;
        if (ToolStepIds.isRagStep(stepId) || TimelineSessionSummaries.isWorkflowRagNode(stepId)) {
            metadata = mergeRagRewriteMetadataForStep(metadata, stepId);
            if (!hasRewriteMetadata(metadata)) {
                metadata = mergeRagRewriteMetadataBetween(metadata, rewriteFromIndex, rewriteToIndex);
            }
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
        // 并行 tool：同轮 PreActing 连续 start 时勿用空 detail 提前 complete；终态由 PostActing 写入
        if (ToolStepIds.isToolStep(state.activeStepId)
                || ThinkStepIds.isThinkStep(state.activeStepId)
                || TimelineStepId.GENERATE.matches(state.activeStepId)
                || TimelineStepId.TASKS.matches(state.activeStepId)) {
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
                    QueryRewriteTrace.size(state.traceMessageId));
        }
    }

    private StepMetadata mergeRagRewriteMetadataForStep(StepMetadata metadata, String stepId) {
        if (state.traceMessageId == null || stepId == null) {
            return metadata;
        }
        List<QueryRewriteOutcome> outcomes = QueryRewriteTrace.outcomesForStep(state.traceMessageId, stepId);
        if (outcomes.isEmpty()) {
            return metadata;
        }
        StepMetadata merged = metadata;
        for (QueryRewriteOutcome outcome : outcomes) {
            merged = StepMetadata.mergeRewrite(merged, outcome);
        }
        return merged;
    }

    private static boolean hasRewriteMetadata(StepMetadata metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.rewriteApplied());
    }

    private StepMetadata mergeRagRewriteMetadataBetween(StepMetadata metadata, int fromIndex, int toIndex) {
        if (state.traceMessageId == null) {
            return metadata;
        }
        QueryRewriteOutcome ragRewrite =
                QueryRewriteTrace.latestBetween(
                        state.traceMessageId, QueryRewriteScenario.RAG.id(), fromIndex, toIndex)
                        .orElse(null);
        QueryRewriteOutcome hydeRewrite =
                QueryRewriteTrace.latestBetween(
                        state.traceMessageId, QueryRewriteScenario.HYDE.id(), fromIndex, toIndex)
                        .orElse(null);
        QueryRewriteOutcome emptyRewrite =
                QueryRewriteTrace.latestBetween(
                        state.traceMessageId, QueryRewriteScenario.EMPTY_RECALL.id(), fromIndex, toIndex)
                        .orElse(null);
        StepMetadata merged = StepMetadata.mergeRewrite(metadata, ragRewrite);
        merged = StepMetadata.mergeRewrite(merged, hydeRewrite);
        return StepMetadata.mergeRewrite(merged, emptyRewrite);
    }

    private StepMetadata mergeRagRewriteMetadataSince(StepMetadata metadata, int fromIndex) {
        if (state.traceMessageId == null) {
            return metadata;
        }
        int toIndex = QueryRewriteTrace.size(state.traceMessageId);
        return mergeRagRewriteMetadataBetween(metadata, fromIndex, toIndex);
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
