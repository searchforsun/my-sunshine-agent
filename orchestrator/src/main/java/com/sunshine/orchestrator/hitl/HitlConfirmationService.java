package com.sunshine.orchestrator.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.generation.GenerationJob;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.plan.PendingInteraction;
import com.sunshine.orchestrator.processing.HitlLabels;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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
    @Lazy
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
        waiters.put(token, new HitlPendingWaiter(generationMessageId, toolId, future));
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
        } catch (java.util.concurrent.CancellationException e) {
            log.info("[HITL] token={} tool={} 等待被中断（暂停/断连）", token, toolId);
            throw new HitlWaitInterruptedException();
        } catch (TimeoutException e) {
            log.warn("[HITL] token={} tool={} 确认超时", token, toolId);
            waiters.remove(token);
            redis.delete(redisKey(token));
            progressBridgeToolStep(timelineBridgeId, HitlLabels.denied());
            StepEventBridge.emit(timelineBridgeId, session -> session.resolveHitlPending(HitlStepMeta.STATUS_DENIED));
            flushHookTimeline(timelineBridgeId);
            return false;
        } catch (Exception e) {
            if (isWaitInterrupted(e)) {
                log.info("[HITL] token={} tool={} 等待被中断（暂停/断连）", token, toolId);
                throw new HitlWaitInterruptedException();
            }
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
        waiters.put(token, new HitlPendingWaiter(genMsgId, toolId, future));
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
        } catch (java.util.concurrent.CancellationException e) {
            log.info("[HITL] workflow token={} tool={} 等待被中断（暂停/断连）", token, toolId);
            throw new HitlWaitInterruptedException();
        } catch (TimeoutException e) {
            log.warn("[HITL] workflow token={} tool={} 确认超时", token, toolId);
            waiters.remove(token);
            redis.delete(redisKey(token));
            emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            emitSessionStep(session, nodeStepId, s -> s.resolveHitlPendingOnStep(nodeStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
            return false;
        } catch (Exception e) {
            if (isWaitInterrupted(e)) {
                log.info("[HITL] workflow token={} tool={} 等待被中断（暂停/断连）", token, toolId);
                throw new HitlWaitInterruptedException();
            }
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

    /** ReAct 续跑：工具步仍 awaiting 时经 GenerationJob 重新下发 step + confirmation 并阻塞 */
    public boolean resumeReactAwaiting(String bridgeId, String assistantMsgId, ProcessingStep toolStep) {
        if (toolStep == null || toolStep.metadata() == null || toolStep.metadata().hitl() == null) {
            return false;
        }
        var hitl = toolStep.metadata().hitl();
        if (!HitlStepMeta.STATUS_AWAITING.equals(hitl.status())) {
            return false;
        }
        String toolStepId = toolStep.id();
        String toolId = com.sunshine.orchestrator.processing.ToolStepIds.catalogToolName(toolStepId);
        if (!StringUtils.hasText(toolId) || !StringUtils.hasText(assistantMsgId)) {
            return false;
        }
        Map<String, String> params = parseParamsSummary(hitl.paramsSummary());
        String displayName = toolCatalogService.displayName(toolId);
        String genMsgId = assistantMsgId.strip();
        ProcessingTimelineSession session = com.sunshine.orchestrator.processing.ProcessingTimelineSupport.newSession();
        session.bindTraceMessageId(genMsgId);
        long startedAt = System.currentTimeMillis();
        emitSessionStep(session, toolStepId, s -> {
            s.startAt(toolStepId, "tool", startedAt);
            s.progress(toolStepId, HitlLabels.awaiting());
        }, genMsgId);
        String token = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waiters.put(token, new HitlPendingWaiter(genMsgId, toolId, future));
        long expiresAt = Instant.now().plusSeconds(properties.getTimeoutSec()).toEpochMilli();
        storeToken(token, genMsgId, toolId, expiresAt);
        String paramsSummary = StringUtils.hasText(hitl.paramsSummary())
                ? hitl.paramsSummary() : summarizeParams(params);
        emitSessionStep(session, toolStepId, s -> s.attachHitlPendingOnStep(
                toolStepId, token, displayName, paramsSummary, expiresAt), genMsgId);
        emitConfirmation(genMsgId, toolId, params, token, expiresAt);
        try {
            boolean approved = future.get(properties.getTimeoutSec(), TimeUnit.SECONDS);
            log.info("[HITL] react resume token={} tool={} step={} approved={}",
                    token, toolId, toolStepId, approved);
            if (approved) {
                emitSessionStep(session, toolStepId,
                        s -> s.progress(toolStepId, HitlLabels.approved(displayName)), genMsgId);
                emitSessionStep(session, toolStepId,
                        s -> s.resolveHitlPendingOnStep(toolStepId, HitlStepMeta.STATUS_APPROVED), genMsgId);
                StepEventBridge.grantHitlPreApproval(genMsgId, toolId, params);
            } else {
                emitSessionStep(session, toolStepId, s -> s.progress(toolStepId, HitlLabels.denied()), genMsgId);
                emitSessionStep(session, toolStepId,
                        s -> s.resolveHitlPendingOnStep(toolStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
            }
            return approved;
        } catch (java.util.concurrent.CancellationException e) {
            log.info("[HITL] react resume token={} tool={} 等待被中断（暂停/断连）", token, toolId);
            throw new HitlWaitInterruptedException();
        } catch (TimeoutException e) {
            log.warn("[HITL] react resume token={} tool={} 确认超时", token, toolId);
            waiters.remove(token);
            redis.delete(redisKey(token));
            emitSessionStep(session, toolStepId, s -> s.progress(toolStepId, HitlLabels.denied()), genMsgId);
            emitSessionStep(session, toolStepId,
                    s -> s.resolveHitlPendingOnStep(toolStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
            return false;
        } catch (Exception e) {
            if (isWaitInterrupted(e)) {
                log.info("[HITL] react resume token={} tool={} 等待被中断（暂停/断连）", token, toolId);
                throw new HitlWaitInterruptedException();
            }
            log.warn("[HITL] react resume token={} tool={} 等待异常: {}", token, toolId, e.getMessage());
            waiters.remove(token);
            redis.delete(redisKey(token));
            return false;
        } finally {
            waiters.remove(token);
            redis.delete(redisKey(token));
        }
    }

    /** 续跑：从 checkpoint 恢复 HITL 待确认，不先调 tool-manager */
    public boolean resumeAwaitingFromCheckpoint(
            WorkflowHitlScope.Binding workflow,
            String generationMessageId,
            PendingInteraction pending,
            String toolId) {
        if (pending == null || !"hitl".equals(pending.kind())) {
            return false;
        }
        String resolvedToolId = StringUtils.hasText(toolId)
                ? toolId.strip()
                : (StringUtils.hasText(pending.hitlToolId()) ? pending.hitlToolId().strip() : "");
        if (!StringUtils.hasText(resolvedToolId)) {
            return false;
        }
        Map<String, String> params = parseParamsSummary(pending.hitlParamsSummary());
        String displayName = toolCatalogService.displayName(resolvedToolId);
        String nodeStepId = workflow.nodeStepId();
        ProcessingTimelineSession session = workflow.session();
        String genMsgId = generationMessageId != null ? generationMessageId : workflow.generationMessageId();
        emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.awaiting()), genMsgId);
        String token = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waiters.put(token, new HitlPendingWaiter(genMsgId, resolvedToolId, future));
        long expiresAt = Instant.now().plusSeconds(properties.getTimeoutSec()).toEpochMilli();
        storeToken(token, genMsgId, resolvedToolId, expiresAt);
        String paramsSummary = StringUtils.hasText(pending.hitlParamsSummary())
                ? pending.hitlParamsSummary() : summarizeParams(params);
        emitSessionStep(session, nodeStepId, s -> s.attachHitlPendingOnStep(
                nodeStepId, token, displayName, paramsSummary, expiresAt), genMsgId);
        emitConfirmation(genMsgId, resolvedToolId, params, token, expiresAt);
        try {
            boolean approved = future.get(properties.getTimeoutSec(), TimeUnit.SECONDS);
            log.info("[HITL] resume token={} tool={} approved={}", token, resolvedToolId, approved);
            if (approved) {
                emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.approved(displayName)), genMsgId);
            } else {
                emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            }
            String hitlStatus = approved ? HitlStepMeta.STATUS_APPROVED : HitlStepMeta.STATUS_DENIED;
            emitSessionStep(session, nodeStepId, s -> s.resolveHitlPendingOnStep(nodeStepId, hitlStatus), genMsgId);
            return approved;
        } catch (java.util.concurrent.CancellationException e) {
            log.info("[HITL] resume token={} tool={} 等待被中断（暂停/断连）", token, resolvedToolId);
            throw new HitlWaitInterruptedException();
        } catch (TimeoutException e) {
            log.warn("[HITL] resume token={} tool={} 确认超时", token, resolvedToolId);
            waiters.remove(token);
            redis.delete(redisKey(token));
            emitSessionStep(session, nodeStepId, s -> s.progress(nodeStepId, HitlLabels.denied()), genMsgId);
            emitSessionStep(session, nodeStepId, s -> s.resolveHitlPendingOnStep(nodeStepId, HitlStepMeta.STATUS_DENIED), genMsgId);
            return false;
        } catch (Exception e) {
            if (isWaitInterrupted(e)) {
                log.info("[HITL] resume token={} tool={} 等待被中断（暂停/断连）", token, resolvedToolId);
                throw new HitlWaitInterruptedException();
            }
            log.warn("[HITL] resume token={} tool={} 等待异常: {}", token, resolvedToolId, e.getMessage());
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
        return shouldConfirmForBridge(toolId, StepEventBridge.resolveHitlBridgeId());
    }

    public boolean shouldConfirmForBridge(String toolId, String bridgeId) {
        if (!properties.isEnabled() || !StepEventBridge.hitlEnabledForBridge(bridgeId)) {
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

    /** 用户暂停 generation：唤醒阻塞中的 HITL 等待，使 cancel 能完成并释放 message 锁 */
    public void cancelWaitersForMessage(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        String target = messageId.strip();
        waiters.entrySet().removeIf(entry -> {
            HitlPendingWaiter waiter = entry.getValue();
            if (!target.equals(waiter.messageId())) {
                return false;
            }
            waiter.future().cancel(true);
            redis.delete(redisKey(entry.getKey()));
            return true;
        });
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

    private static Map<String, String> parseParamsSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : summary.split(",\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static String summarizeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + truncateParamValue(e.getValue()))
                .collect(Collectors.joining(", "));
    }

    /** HITL 确认框参数摘要：单行 key=value，过长截断 */
    private static String truncateParamValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().replace('\n', ' ');
        int maxLen = 120;
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "…";
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

    private static boolean isWaitInterrupted(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HitlWaitInterruptedException || t instanceof java.util.concurrent.CancellationException) {
                return true;
            }
        }
        return false;
    }
}
