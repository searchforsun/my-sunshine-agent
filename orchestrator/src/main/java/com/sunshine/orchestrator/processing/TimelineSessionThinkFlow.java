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
            openNextThink();
            log.debug("[ThinkFlow] begin: tool-sep -> openThink={}", state.currentThinkId);
            return;
        }
        // 无业务 tool 间隔的连续 reasoning（如终态前空转多轮、todo_write 建板后再推理）复用同一 think，
        // 避免堆叠「综合分析」。复用发 RESUME（翻回 running、保留既有 reasoning），不发 pending/start：
        // START 会清空旧 reasoning 导致覆盖之前的思考；此处新 reasoning 应 concat 续写在旧内容后。
        if (state.lastCompletedThinkId != null && !emitter.isStepRunning(state.lastCompletedThinkId)) {
            state.currentThinkId = state.lastCompletedThinkId;
            lifecycle.resume(state.currentThinkId, TimelineStepId.THINK.phase());
            log.debug("[ThinkFlow] begin: reuseThink={}", state.currentThinkId);
            return;
        }
        if (isThinkRunning()) {
            completeThinkIfRunning();
        }
        openNextThink();
        log.debug("[ThinkFlow] begin: freshThink={}", state.currentThinkId);
    }

    void endReasoningRound() {
        long endedAt = System.currentTimeMillis();
        String thinkId = resolveRunningThinkId();
        if (thinkId == null) {
            log.debug("[ThinkFlow] end: noRunningThink");
            return;
        }
        state.lastCompletedThinkId = thinkId;
        if (emitter.isStepRunning(thinkId)) {
            lifecycle.completeAt(thinkId, null, endedAt);
            state.lastCompletedThinkEndedAt = endedAt;
            log.debug("[ThinkFlow] end: completeThink={}", thinkId);
            return;
        }
        state.lastCompletedThinkEndedAt = state.aggregator.get(thinkId)
                .map(ProcessingStep::endedAt)
                .filter(ts -> ts > 0)
                .orElse(endedAt);
        log.debug("[ThinkFlow] end: alreadyDoneThink={}", thinkId);
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
