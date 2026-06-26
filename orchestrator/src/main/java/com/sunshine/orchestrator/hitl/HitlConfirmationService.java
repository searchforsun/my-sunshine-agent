package com.sunshine.orchestrator.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.generation.GenerationJob;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.processing.HitlLabels;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 写工具 HITL — Redis 存 token 元数据，内存 Future 阻塞同实例工具调用。
 * 时间线：先 flush think/tool 步骤，再「将调用 → 等待确认 → 确认/取消 → 执行/跳过」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlConfirmationService {

    private static final String REDIS_KEY_PREFIX = "sunshine:hitl:pending:";

    private final AgentHitlProperties properties;
    private final ToolCatalogService toolCatalogService;
    private final GenerationRegistry generationRegistry;
    private final GenerationFlushScheduler flushScheduler;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, HitlPendingWaiter> waiters = new ConcurrentHashMap<>();

    /** 阻塞等待用户确认；超时或拒绝返回 false */
    public boolean awaitConfirmation(String timelineBridgeId, String toolId, Map<String, String> params) {
        String generationMessageId = StepEventBridge.hitlAssistantMessageId(timelineBridgeId);
        if (generationMessageId == null) {
            generationMessageId = timelineBridgeId;
        }
        return awaitConfirmation(timelineBridgeId, generationMessageId, toolId, params);
    }

    /** 阻塞等待用户确认（显式指定 SSE generation messageId） */
    public boolean awaitConfirmation(
            String timelineBridgeId,
            String generationMessageId,
            String toolId,
            Map<String, String> params) {
        return awaitBridgeConfirmation(timelineBridgeId, generationMessageId, toolId, params);
    }

    private boolean awaitBridgeConfirmation(
            String timelineBridgeId,
            String generationMessageId,
            String toolId,
            Map<String, String> params) {
        String displayName = toolCatalogService.displayName(toolId);
        flushHookTimeline(timelineBridgeId);
        progressBridgeToolStep(timelineBridgeId, HitlLabels.pending(displayName));
        progressBridgeToolStep(timelineBridgeId, HitlLabels.awaiting());

        String token = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waiters.put(token, new HitlPendingWaiter(timelineBridgeId, toolId, future));
        long expiresAt = Instant.now().plusSeconds(properties.getTimeoutSec()).toEpochMilli();
        storeToken(token, generationMessageId, toolId, expiresAt);
        StepEventBridge.emit(timelineBridgeId, session -> session.attachHitlPending(
                token, displayName, summarizeParams(params), expiresAt));
        flushHookTimeline(timelineBridgeId);
        emitConfirmation(generationMessageId, toolId, params, token, expiresAt);
        try {
            boolean approved = future.get(properties.getTimeoutSec(), TimeUnit.SECONDS);
            log.info("[HITL] token={} tool={} approved={}", token, toolId, approved);
            if (approved) {
                progressBridgeToolStep(timelineBridgeId, HitlLabels.approved(displayName));
            } else {
                progressBridgeToolStep(timelineBridgeId, HitlLabels.denied());
            }
            String hitlStatus = approved ? HitlStepMeta.STATUS_APPROVED : HitlStepMeta.STATUS_DENIED;
            StepEventBridge.emit(timelineBridgeId, session -> session.resolveHitlPending(hitlStatus));
            flushHookTimeline(timelineBridgeId);
            return approved;
        } catch (TimeoutException e) {
            log.warn("[HITL] token={} tool={} 确认超时", token, toolId);
            waiters.remove(token);
            redis.delete(redisKey(token));
            progressBridgeToolStep(timelineBridgeId, HitlLabels.denied());
            StepEventBridge.emit(timelineBridgeId, session -> session.resolveHitlPending(HitlStepMeta.STATUS_DENIED));
            flushHookTimeline(timelineBridgeId);
            return false;
        } catch (Exception e) {
            log.warn("[HITL] token={} tool={} 等待异常: {}", token, toolId, e.getMessage());
            waiters.remove(token);
            redis.delete(redisKey(token));
            progressBridgeToolStep(timelineBridgeId, HitlLabels.denied());
            return false;
        } finally {
            waiters.remove(token);
            redis.delete(redisKey(token));
        }
    }

    /** Workflow tool 节点阻塞确认 — binding 由 ExecutionStreamContext.workflowHitl 传入 */
    public boolean awaitWorkflowConfirmation(
            WorkflowHitlScope.Binding workflow,
            String generationMessageId,
            String toolId,
            Map<String, String> params) {
        String displayName = toolCatalogService.displayName(toolId);
        String nodeStepId = workflow.nodeStepId();
        ProcessingTimelineSession session = workflow.session();
        String genMsgId = generationMessageId != null ? generationMessageId : workflow.generationMessageId();
        emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.pending(displayName)), genMsgId);
        emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.awaiting()), genMsgId);

        String token = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waiters.put(token, new HitlPendingWaiter(nodeStepId, toolId, future));
        long expiresAt = Instant.now().plusSeconds(properties.getTimeoutSec()).toEpochMilli();
        storeToken(token, genMsgId, toolId, expiresAt);
        emitSessionStep(session, nodeStepId, s -> s.attachHitlPendingOnStep(
                nodeStepId, token, displayName, summarizeParams(params), expiresAt), genMsgId);
        emitConfirmation(genMsgId, toolId, params, token, expiresAt);
        try {
            boolean approved = future.get(properties.getTimeoutSec(), TimeUnit.SECONDS);
            log.info("[HITL] workflow token={} tool={} approved={}", token, toolId, approved);
            if (approved) {
                emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.approved(displayName)), genMsgId);
            } else {
                emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            }
            String hitlStatus = approved ? HitlStepMeta.STATUS_APPROVED : HitlStepMeta.STATUS_DENIED;
            emitSessionStep(session, nodeStepId, s -> s.resolveHitlPendingOnStep(nodeStepId, hitlStatus), genMsgId);
            return approved;
        } catch (TimeoutException e) {
            log.warn("[HITL] workflow token={} tool={} 确认超时", token, toolId);
            waiters.remove(token);
            redis.delete(redisKey(token));
            emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            emitSessionStep(session, nodeStepId, s -> s.resolveHitlPendingOnStep(nodeStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
            return false;
        } catch (Exception e) {
            log.warn("[HITL] workflow token={} tool={} 等待异常: {}", token, toolId, e.getMessage());
            waiters.remove(token);
            redis.delete(redisKey(token));
            emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            return false;
        } finally {
            waiters.remove(token);
            redis.delete(redisKey(token));
        }
    }

    public boolean shouldConfirm(String toolId) {
        if (!properties.isEnabled() || !StepEventBridge.hitlEnabled()) {
            return false;
        }
        return toolCatalogService.isWriteTool(toolId);
    }

    /** Workflow tool 节点：须 streamCtx 携带 workflowHitl binding */
    public boolean shouldConfirmWorkflow(String toolId, WorkflowHitlScope.Binding binding) {
        if (!properties.isEnabled() || binding == null) {
            return false;
        }
        return toolCatalogService.isWriteTool(toolId);
    }

    private void progressBridgeToolStep(String timelineBridgeId, String activeSummary) {
        StepEventBridge.emit(timelineBridgeId, session -> session.progressCurrentToolStep(activeSummary));
        flushHookTimeline(timelineBridgeId);
    }

    public String rejectionMessage() {
        return properties.getRejectionMessage();
    }

    public String skippedAfterSummary() {
        return HitlLabels.skippedAfter();
    }

    /** 将 Hook 队列中尚未下发的 step 事件刷入 SSE */
    public void flushTimeline(String messageId) {
        flushHookTimeline(messageId);
    }

    /** confirm-tool API */
    public boolean confirm(String token, boolean approved) {
        if (token == null || token.isBlank()) {
            return false;
        }
        HitlPendingWaiter waiter = waiters.remove(token);
        if (waiter != null) {
            waiter.future().complete(approved);
            redis.delete(redisKey(token));
            return true;
        }
        String key = redisKey(token);
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            log.warn("[HITL] confirm 无效 token={}", token);
            return false;
        }
        redis.delete(key);
        log.warn("[HITL] confirm token={} 无本地 waiter（可能已超时或其它实例）", token);
        return false;
    }

    private void emitSessionStep(
            ProcessingTimelineSession session,
            String stepId,
            java.util.function.Consumer<ProcessingTimelineSession> action,
            String generationMessageId) {
        java.util.List<StreamToken> tokens =
                com.sunshine.orchestrator.processing.ProcessingTimelineSupport.run(session, () -> action.accept(session));
        generationRegistry.findByMessageId(generationMessageId).ifPresent(job ->
                tokens.forEach(job::emitStreamToken));
    }

    private void flushHookTimeline(String timelineBridgeId) {
        String generationMessageId = StepEventBridge.hitlAssistantMessageId(timelineBridgeId);
        if (generationMessageId == null) {
            generationMessageId = timelineBridgeId;
        }
        String genId = generationMessageId;
        generationRegistry.findByMessageId(genId).ifPresent(job ->
                StepEventBridge.drainHookQueueToGeneration(timelineBridgeId, job::emitStreamToken));
    }

    private void emitConfirmation(
            String messageId,
            String toolId,
            Map<String, String> params,
            String token,
            long expiresAt) {
        String displayName = toolCatalogService.displayName(toolId);
        String paramsSummary = summarizeParams(params);
        String wire = flushScheduler.metaConfirmation(toolId, displayName, paramsSummary, token, expiresAt);
        generationRegistry.findByMessageId(messageId).ifPresentOrElse(
                job -> job.emitOutbound(wire),
                () -> log.warn("[HITL] 无活跃 generation messageId={}，确认事件未下发", messageId));
    }

    private static String summarizeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    private void storeToken(String token, String messageId, String toolId, long expiresAt) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("toolId", toolId);
        payload.put("expiresAt", String.valueOf(expiresAt));
        try {
            redis.opsForValue().set(
                    redisKey(token),
                    objectMapper.writeValueAsString(payload),
                    Duration.ofSeconds(properties.getTimeoutSec() + 30));
        } catch (JsonProcessingException e) {
            log.warn("[HITL] token 序列化失败: {}", e.getMessage());
        }
    }

    private static String redisKey(String token) {
        return REDIS_KEY_PREFIX + token;
    }
}
