package com.sunshine.orchestrator.hitl;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.generation.GenerationJob;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** HITL 时间线 / SSE 刷写 — Hook 队列 drain + confirmation 事件 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HitlTimelineBridge {

    @Lazy
    private final GenerationRegistry generationRegistry;
    private final GenerationFlushScheduler flushScheduler;
    private final ToolCatalogService toolCatalogService;

    /** messageId → 续跑重规划代数；await 期间代数变化则中止刷 SSE */
    private final ConcurrentHashMap<String, Long> hitlEpochByMessage = new ConcurrentHashMap<>();

    public void invalidateForMessageRestart(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        hitlEpochByMessage.merge(messageId.strip(), 0L, (k, v) -> v + 1);
    }

    public long currentHitlEpoch(String messageId) {
        return hitlEpochByMessage.getOrDefault(messageId.strip(), 0L);
    }

    public void ensureHitlEpoch(String messageId, long epoch) {
        if (!StringUtils.hasText(messageId)) {
            throw new HitlWaitInterruptedException();
        }
        if (currentHitlEpoch(messageId) != epoch) {
            throw new HitlWaitInterruptedException();
        }
    }

    public String resolveBoundGenerationId(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return null;
        }
        return generationRegistry.findByMessageId(messageId.strip())
                .map(GenerationJob::getGenerationId)
                .orElse(null);
    }

    public void ensureGenerationBound(String messageId, String boundGenerationId) {
        if (!isGenerationBound(messageId, boundGenerationId)) {
            throw new HitlWaitInterruptedException();
        }
    }

    public boolean isGenerationBound(String messageId, String boundGenerationId) {
        if (!StringUtils.hasText(messageId) || !StringUtils.hasText(boundGenerationId)) {
            return false;
        }
        return generationRegistry.findByMessageId(messageId.strip())
                .map(job -> boundGenerationId.equals(job.getGenerationId()))
                .orElse(false);
    }

    public boolean isActiveTimelineBridge(String timelineBridgeId) {
        return StepEventBridge.isHookBridgeActive(timelineBridgeId);
    }

    public void progressBridgeToolStep(
            String timelineBridgeId,
            String activeSummary,
            String generationMessageId,
            String boundGenerationId,
            long epoch) {
        if (!isActiveTimelineBridge(timelineBridgeId)) {
            return;
        }
        ensureHitlEpoch(generationMessageId, epoch);
        if (!isGenerationBound(generationMessageId, boundGenerationId)) {
            return;
        }
        StepEventBridge.emit(timelineBridgeId, session -> session.progressCurrentToolStep(activeSummary));
        flushHookTimeline(timelineBridgeId, generationMessageId, boundGenerationId);
    }

    /** 按工具步精确 progress（一轮多 tool_calls 时 currentToolStepId 不可信，须用 toolUse→stepId 定位） */
    public void progressBridgeToolStepOnStep(
            String timelineBridgeId,
            String stepId,
            String activeSummary,
            String generationMessageId,
            String boundGenerationId,
            long epoch) {
        if (stepId == null || stepId.isBlank()) {
            return;
        }
        if (!isActiveTimelineBridge(timelineBridgeId)) {
            return;
        }
        ensureHitlEpoch(generationMessageId, epoch);
        if (!isGenerationBound(generationMessageId, boundGenerationId)) {
            return;
        }
        StepEventBridge.emit(timelineBridgeId, session -> session.progress(stepId, activeSummary));
        flushHookTimeline(timelineBridgeId, generationMessageId, boundGenerationId);
    }

    public void flushTimeline(String timelineBridgeId) {
        String generationMessageId = StepEventBridge.hitlAssistantMessageId(timelineBridgeId);
        if (generationMessageId == null) {
            generationMessageId = timelineBridgeId;
        }
        String boundGenerationId = resolveBoundGenerationId(generationMessageId);
        if (boundGenerationId == null) {
            return;
        }
        flushHookTimeline(timelineBridgeId, generationMessageId, boundGenerationId);
    }

    public void flushHookTimeline(String timelineBridgeId, String generationMessageId, String boundGenerationId) {
        if (!isActiveTimelineBridge(timelineBridgeId)) {
            return;
        }
        if (!isGenerationBound(generationMessageId, boundGenerationId)) {
            return;
        }
        String genId = generationMessageId != null ? generationMessageId : timelineBridgeId;
        generationRegistry.findByMessageId(genId)
                .filter(job -> boundGenerationId.equals(job.getGenerationId()))
                .ifPresent(job -> StepEventBridge.drainHookQueueToGeneration(
                        timelineBridgeId, job::emitStreamToken));
    }

    public void emitSessionStep(
            ProcessingTimelineSession session,
            String stepId,
            Consumer<ProcessingTimelineSession> action,
            String generationMessageId) {
        List<StreamToken> tokens = ProcessingTimelineSupport.run(session, () -> action.accept(session));
        generationRegistry.findByMessageId(generationMessageId).ifPresent(job ->
                tokens.forEach(job::emitStreamToken));
    }

    public void emitConfirmation(
            String messageId,
            String toolId,
            Map<String, String> params,
            String token,
            long expiresAt) {
        emitConfirmation(messageId, toolId, params, token, expiresAt, -1L, null);
    }

    public void emitConfirmation(
            String messageId,
            String toolId,
            Map<String, String> params,
            String token,
            long expiresAt,
            long epoch,
            String boundGenerationId) {
        if (epoch >= 0) {
            ensureHitlEpoch(messageId, epoch);
        }
        if (boundGenerationId != null && !isGenerationBound(messageId, boundGenerationId)) {
            throw new HitlWaitInterruptedException();
        }
        if (boundGenerationId == null && !isActiveGeneration(messageId)) {
            throw new HitlWaitInterruptedException();
        }
        String displayName = toolCatalogService.displayName(toolId);
        String paramsSummary = HitlParamSupport.summarizeParams(params);
        String wire = flushScheduler.metaConfirmation(toolId, displayName, paramsSummary, token, expiresAt);
        if (boundGenerationId != null) {
            generationRegistry.findByMessageId(messageId)
                    .filter(job -> boundGenerationId.equals(job.getGenerationId()))
                    .ifPresentOrElse(
                            job -> job.emitOutbound(wire),
                            () -> {
                                throw new HitlWaitInterruptedException();
                            });
            return;
        }
        generationRegistry.findByMessageId(messageId).ifPresentOrElse(
                job -> job.emitOutbound(wire),
                () -> log.warn("[HITL] 无活跃 generation messageId={}，确认事件未下发", messageId));
    }

    private boolean isActiveGeneration(String generationMessageId) {
        if (!StringUtils.hasText(generationMessageId)) {
            return false;
        }
        return generationRegistry.findByMessageId(generationMessageId.strip()).isPresent();
    }
}
