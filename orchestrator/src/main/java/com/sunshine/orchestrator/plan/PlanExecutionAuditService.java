package com.sunshine.orchestrator.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.audit.AuditEvent;
import com.sunshine.orchestrator.audit.AuditPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** plan-workflow 重试与降级审计事件 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanExecutionAuditService {

    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void created(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId) {
        publish(conversationId, messageId, userId, tenantId, "plan.created", planId, Map.of());
    }

    public void validated(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            int nodeCount) {
        publish(conversationId, messageId, userId, tenantId, "plan.validated", planId, Map.of(
                "nodeCount", nodeCount));
    }

    public void completed(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            String terminalStatus) {
        publish(conversationId, messageId, userId, tenantId, "plan.completed", planId, Map.of(
                "terminalStatus", terminalStatus));
    }

    public void failed(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            String reason) {
        publish(conversationId, messageId, userId, tenantId, "plan.failed", planId, Map.of(
                "reason", reason != null ? reason : ""));
    }

    public void plannerAttempt(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            PlannerAttempt attempt) {
        publish(conversationId, messageId, userId, tenantId, "plan.planner_attempt", planId, Map.of(
                "attemptNo", attempt.attemptNo(),
                "phase", attempt.phase(),
                "status", attempt.status(),
                "error", attempt.error()));
    }

    public void nodeAttempt(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            String nodeId,
            PlanNodeAttempt attempt,
            int attemptCount) {
        publish(conversationId, messageId, userId, tenantId, "plan.node.attempt", planId, Map.of(
                "nodeId", nodeId,
                "attemptNo", attempt.attemptNo(),
                "status", attempt.status(),
                "errorClass", attempt.errorClass(),
                "summary", attempt.summary(),
                "attemptCount", attemptCount));
    }

    public void fallbackReact(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            String reason) {
        publish(conversationId, messageId, userId, tenantId, "plan.fallback_react", planId, Map.of(
                "reason", reason));
    }

    private void publish(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String eventType,
            String planId,
            Map<String, Object> fields) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(fields);
            payload.put("planId", planId);
            String payloadJson = objectMapper.writeValueAsString(payload);
            auditPublisher.publish(new AuditEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    conversationId,
                    messageId,
                    userId,
                    tenantId,
                    eventType,
                    "ok",
                    null,
                    0,
                    payloadJson,
                    Instant.now()));
        } catch (Exception e) {
            log.warn("[PlanExecutionAudit] 事件构建失败 type={}: {}", eventType, e.getMessage());
        }
    }
}
