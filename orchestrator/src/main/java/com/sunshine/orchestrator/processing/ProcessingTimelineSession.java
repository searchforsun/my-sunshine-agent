package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.routing.ExecutionPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * ReAct 时间线会话：think 边界对齐 AgentScope PreReasoning/PostReasoning Hook，工具步骤由 PreActing/PostActing 写入。
 */
public final class ProcessingTimelineSession {

    private final TimelineSessionState state = new TimelineSessionState();
    private final TimelineSessionEmitter emitter = new TimelineSessionEmitter(state);
    private final TimelineSessionSummaries summaries = new TimelineSessionSummaries(state);
    private final TimelineSessionCompletions completions = new TimelineSessionCompletions(state, emitter, summaries);
    private final TimelineSessionLifecycle lifecycle = new TimelineSessionLifecycle(state, emitter, summaries, completions);
    private final TimelineSessionToolFlow tools = new TimelineSessionToolFlow(state, emitter, lifecycle);
    private final TimelineSessionThinkFlow think = new TimelineSessionThinkFlow(state, emitter, lifecycle);

    public ContentSegmentCoordinator contentSegments() {
        return state.contentSegments;
    }

    public void enqueueAuxiliary(StreamToken token) {
        if (token != null) {
            state.auxiliaryTokens.add(token);
        }
    }

    public List<StreamToken> drainAuxiliaryTokens() {
        if (state.auxiliaryTokens.isEmpty()) {
            return List.of();
        }
        List<StreamToken> copy = new ArrayList<>(state.auxiliaryTokens);
        state.auxiliaryTokens.clear();
        return copy;
    }

    public void closeContentSegment() {
        state.contentSegments.closeIfOpen(new ArrayList<>(), this::enqueueAuxiliary);
    }

    public void bindStepDisplayName(String stepId, String displayName) {
        emitter.bindStepDisplayName(stepId, displayName);
    }

    public void bindTraceMessageId(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            state.traceMessageId = messageId.strip();
        }
    }

    public void recordToolCompleted(String displayName) {
        if (displayName != null && !displayName.isBlank()) {
            state.lastCompletedToolDisplayName = displayName.strip();
        }
    }

    public String beginToolStep(String baseStepId, String phase) {
        return tools.beginToolStep(baseStepId, phase);
    }

    public void completeToolStep(String detail) {
        tools.completeToolStep(detail);
    }

    public void skipCurrentToolStep(String afterSummary) {
        tools.skipCurrentToolStep(afterSummary);
    }

    public void progressCurrentToolStep(String activeSummary) {
        tools.progressCurrentToolStep(activeSummary);
    }

    public String currentToolStepId() {
        return tools.currentToolStepId();
    }

    public void attachHitlPending(String token, String toolDisplayName, String paramsSummary, long expiresAt) {
        tools.attachHitlPending(token, toolDisplayName, paramsSummary, expiresAt);
    }

    public void resolveHitlPending(String status) {
        tools.resolveHitlPending(status);
    }

    public void attachHitlPendingOnStep(
            String stepId, String token, String toolDisplayName, String paramsSummary, long expiresAt) {
        tools.attachHitlPendingOnStep(stepId, token, toolDisplayName, paramsSummary, expiresAt);
    }

    public void resolveHitlPendingOnStep(String stepId, String status) {
        tools.resolveHitlPendingOnStep(stepId, status);
    }

    public void attachNodeRecoveryOnStep(String stepId, String token, String errorMessage, long expiresAt) {
        tools.attachNodeRecoveryOnStep(stepId, token, errorMessage, expiresAt);
    }

    public void resolveNodeRecoveryOnStep(String stepId, String status) {
        tools.resolveNodeRecoveryOnStep(stepId, status);
    }

    public void updateNodeAttemptsOnStep(String stepId, List<NodeAttemptMeta> attempts) {
        tools.updateNodeAttemptsOnStep(stepId, attempts);
    }

    public String activeStepId() {
        return state.activeStepId;
    }

    public void appendDelta(String channel, String text) {
        if (state.activeStepId == null || channel == null || text == null || text.isEmpty()) {
            return;
        }
        appendDelta(state.activeStepId, channel, text);
    }

    public void appendDelta(String stepId, String channel, String text) {
        emitter.appendDelta(stepId, channel, text);
    }

    public void bindUserQuery(String query) {
        if (query != null && !query.isBlank()) {
            state.userQuery = query.strip();
        }
    }

    public String userQuery() {
        return state.userQuery;
    }

    public void onStepChanged(Consumer<ProcessingStep> listener) {
        emitter.onStepChanged(listener);
    }

    public void addStepListener(Consumer<ProcessingStep> listener) {
        emitter.addStepListener(listener);
    }

    Consumer<ProcessingStep> currentListener() {
        return emitter.currentListener();
    }

    public boolean hasStep(String stepId) {
        return emitter.hasStep(stepId);
    }

    public void pending(String stepId, String phase) {
        lifecycle.pending(stepId, phase);
    }

    public void start(String stepId, String phase) {
        lifecycle.start(stepId, phase);
    }

    public void startAt(String stepId, String phase, long startedAt) {
        lifecycle.startAt(stepId, phase, startedAt);
    }

    public void progress(String stepId, String activeSummary) {
        lifecycle.progress(stepId, activeSummary);
    }

    public void complete(String stepId, String detail) {
        lifecycle.complete(stepId, detail);
    }

    public void completeIntent(ExecutionPlan plan) {
        completions.completeIntent(plan);
    }

    public void completeIntent(ExecutionPlan plan, com.sunshine.orchestrator.rewrite.QueryRewriteOutcome intentRewrite) {
        completions.completeIntent(plan, intentRewrite);
    }

    public void completePlanAt(String after, String detail, long endedAt) {
        completions.completePlanAt(after, detail, endedAt);
    }

    public void beginPlanAwaitingApproval(String detail, StepMetadata metadata) {
        completions.beginPlanAwaitingApproval(detail, metadata);
    }

    public void updatePlanApproval(StepMetadata metadata, String activeSummary) {
        completions.updatePlanApproval(metadata, activeSummary);
    }

    public void completeSkillLoad(String skillId) {
        completions.completeSkillLoad(skillId);
    }

    public void completeAt(String stepId, String detail, long endedAt) {
        completions.completeAt(stepId, detail, endedAt);
    }

    public void completeAt(String stepId, String summaryLine, String expandDetail, long endedAt) {
        completions.completeAt(stepId, summaryLine, expandDetail, endedAt);
    }

    public void fail(String stepId, String detail) {
        lifecycle.fail(stepId, detail);
    }

    public void pause(String stepId, String detail) {
        lifecycle.pause(stepId, detail);
    }

    public void terminate(String stepId, String detail) {
        lifecycle.terminate(stepId, detail);
    }

    public void skip(String stepId, String afterSummary) {
        lifecycle.skip(stepId, afterSummary);
    }

    public String currentThinkStepId() {
        return think.currentThinkStepId();
    }

    public String openNextThink() {
        return think.openNextThink();
    }

    public boolean isThinkRunning() {
        return think.isThinkRunning();
    }

    public void beginReasoningRound() {
        think.beginReasoningRound(this::closeContentSegment);
    }

    public void endReasoningRound() {
        think.endReasoningRound();
    }

    public void ingestStreamingContentDelta(String delta) {
        think.ingestStreamingContentDelta(delta, this::enqueueAuxiliary);
    }

    public String contentSegmentBaseline() {
        return think.contentSegmentBaseline();
    }

    public void noteToolCallPending() {
        tools.noteToolCallPending();
    }

    public void noteToolCallDone() {
        tools.noteToolCallDone();
    }

    public boolean hasPendingToolCalls() {
        return tools.hasPendingToolCalls();
    }

    public String contentAnchorAfterStepId() {
        return think.contentAnchorAfterStepId();
    }

    public void completeThinkIfRunning() {
        think.completeThinkIfRunning();
    }

    public void completeThinkParallelAt(long endedAt) {
        think.completeThinkParallelAt(endedAt);
    }

    public List<ProcessingStep> snapshot() {
        return emitter.snapshot();
    }

    public Optional<ProcessingStep> lastChanged() {
        return emitter.lastChanged();
    }
}
