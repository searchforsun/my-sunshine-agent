package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 子 Agent 执行审计 — 独立落库，不写主 chat_message.reasoning */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentAuditService {

    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void subAgentRun(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            String nodeId,
            String runId,
            String skillId,
            List<String> toolCalls,
            String outputSummary,
            String status) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("nodeId", nodeId);
            if (planId != null && !planId.isBlank()) {
                payload.put("planId", planId);
            }
            if (skillId != null && !skillId.isBlank()) {
                payload.put("skillId", skillId);
            }
            payload.put("toolCalls", toolCalls != null ? toolCalls : List.of());
            payload.put("outputSummary", outputSummary != null ? outputSummary : "");
            String payloadJson = objectMapper.writeValueAsString(payload);
            auditPublisher.publish(new AuditEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    conversationId,
                    messageId,
                    userId,
                    tenantId,
                    "sub_agent_run",
                    status != null ? status : "ok",
                    null,
                    outputSummary != null ? outputSummary.length() : 0,
                    payloadJson,
                    Instant.now()));
        } catch (Exception e) {
            log.warn("[SubAgentAudit] 事件构建失败 nodeId={}: {}", nodeId, e.getMessage());
        }
    }
}
