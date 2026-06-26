package com.sunshine.orchestrator.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeTimeline;
import com.sunshine.orchestrator.execution.retry.OnFailureAction;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.processing.NodeRecoveryMeta;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Plan-Workflow 节点失败 — 阻塞等待用户重试/终止 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowNodeRecoveryService {

    private static final String REDIS_KEY_PREFIX = "sunshine:workflow-recovery:";

    private final AgentHitlProperties properties;
    private final GenerationRegistry generationRegistry;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, RecoveryWaiter> waiters = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return properties.isEnabled() && properties.isWorkflowNodeRecovery();
    }

    /**
     * 阻塞等待用户选择；返回 RETRY 时调用方须重跑该节点，TERMINATE/TIMEOUT 时 runSession 已 abort。
     */
    public WorkflowRecoveryAction awaitRecovery(
            ProcessingTimelineSession session,
            String nodeId,
            String generationMessageId,
            String errorMessage,
            WorkflowRunSession runSession) {
        String stepId = WorkflowNodeTimeline.stepId(nodeId);
        String err = StringUtils.hasText(errorMessage) ? errorMessage.strip() : "节点执行失败";
        emitSessionStep(session, stepId, s -> s.fail(stepId, err), generationMessageId);

        String token = UUID.randomUUID().toString();
        CompletableFuture<WorkflowRecoveryAction> future = new CompletableFuture<>();
        waiters.put(token, new RecoveryWaiter(nodeId, future));
        long expiresAt = Instant.now().plusSeconds(properties.getTimeoutSec()).toEpochMilli();
        storeToken(token, generationMessageId, nodeId, expiresAt);
        emitSessionStep(session, stepId, s -> s.attachNodeRecoveryOnStep(
                stepId, token, err, expiresAt), generationMessageId);
        try {
            WorkflowRecoveryAction action = future.get(properties.getTimeoutSec(), TimeUnit.SECONDS);
            log.info("[WorkflowRecovery] token={} node={} action={}", token, nodeId, action);
            String resolved = switch (action) {
                case RETRY -> NodeRecoveryMeta.STATUS_RETRY;
                case SKIP -> NodeRecoveryMeta.STATUS_SKIPPED;
                default -> NodeRecoveryMeta.STATUS_TERMINATED;
            };
            emitSessionStep(session, stepId, s -> {
                s.resolveNodeRecoveryOnStep(stepId, resolved);
                if (NodeRecoveryMeta.STATUS_TERMINATED.equals(resolved)) {
                    s.terminate(stepId, err);
                }
            }, generationMessageId);
            if (action == WorkflowRecoveryAction.TERMINATE || action == WorkflowRecoveryAction.TIMEOUT) {
                runSession.abort(OnFailureAction.FAIL_FAST, "用户终止流程: " + err);
            }
            return action;
        } catch (TimeoutException e) {
            log.warn("[WorkflowRecovery] token={} node={} 等待超时", token, nodeId);
            waiters.remove(token);
            redis.delete(redisKey(token));
            emitSessionStep(session, stepId, s -> {
                s.resolveNodeRecoveryOnStep(stepId, NodeRecoveryMeta.STATUS_TERMINATED);
                s.terminate(stepId, err);
            }, generationMessageId);
            runSession.abort(OnFailureAction.FAIL_FAST, "节点恢复超时: " + err);
            return WorkflowRecoveryAction.TIMEOUT;
        } catch (Exception e) {
            log.warn("[WorkflowRecovery] token={} node={} 等待异常: {}", token, nodeId, e.getMessage());
            waiters.remove(token);
            redis.delete(redisKey(token));
            runSession.abort(OnFailureAction.FAIL_FAST, err);
            return WorkflowRecoveryAction.TERMINATE;
        } finally {
            waiters.remove(token);
            redis.delete(redisKey(token));
        }
    }

    public boolean confirm(String token, String action) {
        if (token == null || token.isBlank()) {
            return false;
        }
        WorkflowRecoveryAction resolved = parseAction(action);
        if (resolved == null) {
            return false;
        }
        RecoveryWaiter waiter = waiters.remove(token);
        if (waiter != null) {
            waiter.future().complete(resolved);
            redis.delete(redisKey(token));
            return true;
        }
        String key = redisKey(token);
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            log.warn("[WorkflowRecovery] confirm 无效 token={}", token);
            return false;
        }
        redis.delete(key);
        log.warn("[WorkflowRecovery] confirm token={} 无本地 waiter（可能已超时或其它实例）", token);
        return false;
    }

    private static WorkflowRecoveryAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        return switch (action.strip().toLowerCase()) {
            case "retry" -> WorkflowRecoveryAction.RETRY;
            case "skip" -> WorkflowRecoveryAction.SKIP;
            case "terminate", "stop" -> WorkflowRecoveryAction.TERMINATE;
            default -> null;
        };
    }

    private void emitSessionStep(
            ProcessingTimelineSession session,
            String stepId,
            java.util.function.Consumer<ProcessingTimelineSession> action,
            String generationMessageId) {
        java.util.List<StreamToken> tokens =
                ProcessingTimelineSupport.run(session, () -> action.accept(session));
        generationRegistry.findByMessageId(generationMessageId).ifPresent(job ->
                tokens.forEach(job::emitStreamToken));
    }

    private void storeToken(String token, String messageId, String nodeId, long expiresAt) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("nodeId", nodeId);
        payload.put("expiresAt", String.valueOf(expiresAt));
        try {
            redis.opsForValue().set(
                    redisKey(token),
                    objectMapper.writeValueAsString(payload),
                    Duration.ofSeconds(properties.getTimeoutSec() + 30));
        } catch (JsonProcessingException e) {
            log.warn("[WorkflowRecovery] token 序列化失败: {}", e.getMessage());
        }
    }

    private static String redisKey(String token) {
        return REDIS_KEY_PREFIX + token;
    }

    private record RecoveryWaiter(String nodeId, CompletableFuture<WorkflowRecoveryAction> future) {
    }
}
