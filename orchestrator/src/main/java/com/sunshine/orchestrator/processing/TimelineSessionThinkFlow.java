package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import lombok.extern.slf4j.Slf4j;

/** ReAct think 轮次 + 正文段锚点 */
@Slf4j
final class TimelineSessionThinkFlow {

    private final TimelineSessionState state;
    private final TimelineSessionEmitter emitter;
    private final TimelineSessionLifecycle lifecycle;

    TimelineSessionThinkFlow(
            TimelineSessionState state,
            TimelineSessionEmitter emitter,
            TimelineSessionLifecycle lifecycle) {
        this.state = state;
        this.emitter = emitter;
        this.lifecycle = lifecycle;
    }

    String currentThinkStepId() {
        return state.currentThinkId;
    }

    String openNextThink() {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            return state.currentThinkId;
        }
        state.thinkIteration++;
        state.currentThinkId = ThinkStepIds.forIteration(state.thinkIteration);
        lifecycle.pending(state.currentThinkId, TimelineStepId.THINK.phase());
        lifecycle.start(state.currentThinkId, TimelineStepId.THINK.phase());
        return state.currentThinkId;
    }

    boolean isThinkRunning() {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            return true;
        }
        return emitter.snapshot().stream()
                .anyMatch(s -> ThinkStepIds.isThinkStep(s.id()) && emitter.isStepRunning(s.id()));
    }

    void beginReasoningRound(Runnable closeContentSegment) {
        closeContentSegment.run();
        if (state.toolCompletedSinceLastThink) {
            state.toolCompletedSinceLastThink = false;
            if (isThinkRunning()) {
                completeThinkIfRunning();
            }
            // 业务 tool 后：下一轮有 Thinking 再开新 think；无 Thinking（纯 tool_call）不开空步
            state.pendingThinkOpen = TimelineSessionState.PendingThinkOpen.FRESH;
            log.debug("[ThinkFlow] begin: pendingFresh afterTool");
            return;
        }
        // 无业务 tool 间隔的连续 reasoning：有 Thinking 时 RESUME 复用；无 Thinking 不开空 running
        if (state.lastCompletedThinkId != null && !emitter.isStepRunning(state.lastCompletedThinkId)) {
            state.pendingThinkOpen = TimelineSessionState.PendingThinkOpen.REUSE;
            log.debug("[ThinkFlow] begin: pendingReuse think={}", state.lastCompletedThinkId);
            return;
        }
        if (isThinkRunning()) {
            completeThinkIfRunning();
        }
        state.pendingThinkOpen = TimelineSessionState.PendingThinkOpen.FRESH;
        log.debug("[ThinkFlow] begin: pendingFresh");
    }

    /** 首个 ThinkingBlockStart/Delta：按 pending 意图开/复用 think */
    void ensureThinkOpen() {
        if (isThinkRunning()) {
            return;
        }
        TimelineSessionState.PendingThinkOpen intent = state.pendingThinkOpen;
        state.pendingThinkOpen = TimelineSessionState.PendingThinkOpen.NONE;
        if (intent == TimelineSessionState.PendingThinkOpen.REUSE
                && state.lastCompletedThinkId != null) {
            state.currentThinkId = state.lastCompletedThinkId;
            lifecycle.resume(state.currentThinkId, TimelineStepId.THINK.phase());
            log.debug("[ThinkFlow] ensureOpen: reuseThink={}", state.currentThinkId);
            return;
        }
        openNextThink();
        log.debug("[ThinkFlow] ensureOpen: openThink={}", state.currentThinkId);
    }

    void endReasoningRound() {
        long endedAt = System.currentTimeMillis();
        String thinkId = resolveRunningThinkId();
        if (thinkId == null || !emitter.isStepRunning(thinkId)) {
            // 本轮未 materialize think（无 ThinkingBlock）：保留 pending 意图，
            // 供 onActing think_summary / 正文补开本轮 think，避免落到上一轮已 done 的旧 think
            log.debug("[ThinkFlow] end: noRunningThink keepPending={}", state.pendingThinkOpen);
            return;
        }
        state.pendingThinkOpen = TimelineSessionState.PendingThinkOpen.NONE;
        state.lastCompletedThinkId = thinkId;
        lifecycle.completeAt(thinkId, null, endedAt);
        state.lastCompletedThinkEndedAt = endedAt;
        log.debug("[ThinkFlow] end: completeThink={}", thinkId);
    }

    private String resolveRunningThinkId() {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            return state.currentThinkId;
        }
        return emitter.snapshot().stream()
                .filter(s -> ThinkStepIds.isThinkStep(s.id()) && emitter.isStepRunning(s.id()))
                .map(ProcessingStep::id)
                .findFirst()
                .orElse(state.currentThinkId);
    }

    void ingestStreamingContentDelta(String delta, java.util.function.Consumer<com.sunshine.orchestrator.client.StreamToken> sink) {
        // 勿用 isBlank：Gateway 常单独下发 " " / "\n"，丢弃会破坏 Markdown 表格/换行
        if (delta == null || delta.isEmpty()) {
            return;
        }
        // 无 ThinkingBlock 直接出正文的轮（如终态作答）：先按 pending 意图补开本轮 think，
        // 使正文锚定到本轮新建 think（位于最后一个工具之后），而非上一轮已 done 的旧 think
        if (state.pendingThinkOpen != TimelineSessionState.PendingThinkOpen.NONE) {
            ensureThinkOpen();
        }
        completeThinkIfRunning();
        String anchor = contentAnchorAfterStepId();
        if (anchor == null || anchor.isBlank()) {
            return;
        }
        state.contentSegments.ingest(delta, anchor, sink);
    }

    /** think_summary 工具参数摘要 → 最近一轮 think 步 step_summary（写回 aggregator + 下发前端主行） */
    void applyThinkStepSummary(String summary, java.util.function.Consumer<com.sunshine.orchestrator.client.StreamToken> sink) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        // 无 ThinkingBlock 的轮（含终态作答轮）：think_summary 到达时按 pending 意图补开本轮 think，
        // 禁止把终态摘要写进上一轮已 done 的 think（否则该 think 被重新贴标、正文锚点跑到最后一个工具之前）
        if (state.pendingThinkOpen != TimelineSessionState.PendingThinkOpen.NONE) {
            ensureThinkOpen();
        }
        String thinkId = state.currentThinkId;
        if (thinkId == null) {
            thinkId = state.lastCompletedThinkId;
        }
        if (thinkId == null) {
            log.info("[ThinkSummary] applyStepSummary drop: noThinkId cur={} last={} summary={}",
                    state.currentThinkId, state.lastCompletedThinkId, summary);
            return;
        }
        String trimmed = summary.strip();
        log.info("[ThinkSummary] applyStepSummary thinkId={} cur={} last={} running={} summary={}",
                thinkId, state.currentThinkId, state.lastCompletedThinkId,
                emitter.isStepRunning(thinkId), trimmed);
        state.aggregator.appendDelta(thinkId, "step_summary", trimmed, System.currentTimeMillis());
        sink.accept(com.sunshine.orchestrator.client.StreamToken.stepDelta(thinkId, "step_summary", trimmed));
    }

    String contentSegmentBaseline() {
        return state.contentSegments.currentBaseline();
    }

    String contentAnchorAfterStepId() {
        String lastDoneThink = null;
        for (ProcessingStep step : emitter.snapshot()) {
            if (ThinkStepIds.isThinkStep(step.id()) && "done".equals(step.lifecycle())) {
                lastDoneThink = step.id();
            }
        }
        return lastDoneThink;
    }

    void completeThinkIfRunning() {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            lifecycle.complete(state.currentThinkId, null);
            return;
        }
        emitter.snapshot().stream()
                .filter(s -> ThinkStepIds.isThinkStep(s.id()) && emitter.isStepRunning(s.id()))
                .map(ProcessingStep::id)
                .findFirst()
                .ifPresent(id -> lifecycle.complete(id, null));
    }

    void completeThinkParallelAt(long endedAt) {
        if (state.currentThinkId != null && emitter.isStepRunning(state.currentThinkId)) {
            lifecycle.completeAt(state.currentThinkId, null, endedAt);
            return;
        }
        emitter.snapshot().stream()
                .filter(s -> ThinkStepIds.isThinkStep(s.id()) && emitter.isStepRunning(s.id()))
                .map(ProcessingStep::id)
                .findFirst()
                .ifPresent(id -> lifecycle.completeAt(id, null, endedAt));
    }
}
