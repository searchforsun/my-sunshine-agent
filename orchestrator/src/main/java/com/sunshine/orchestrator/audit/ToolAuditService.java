package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.DesensitizeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 工具调用审计 — workflow / ReAct 路径统一落库 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAuditService {

    private final AuditPublisher auditPublisher;
    private final DesensitizeClient desensitizeClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void toolCall(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            String nodeId,
            String toolId,
            Map<String, String> params,
            String outputSummary,
            String status) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolId", toolId != null ? toolId : "");
            if (StringUtils.hasText(nodeId)) {
                payload.put("nodeId", nodeId);
            }
            if (StringUtils.hasText(planId)) {
                payload.put("planId", planId);
            }
            payload.put("params", scrubParams(params));
            payload.put("outputSummary", outputSummary != null ? outputSummary : "");
            String payloadJson = objectMapper.writeValueAsString(payload);
            auditPublisher.publish(new AuditEvent(
                    UUID.randomUUID().toString().replace("-", ""),
                    conversationId != null ? conversationId : "",
                    messageId != null ? messageId : "",
                    userId != null ? userId : "",
                    tenantId != null ? tenantId : "default",
                    "tool.call",
                    status != null ? status : "ok",
                    null,
                    outputSummary != null ? outputSummary.length() : 0,
                    payloadJson,
                    Instant.now()));
        } catch (Exception e) {
            log.warn("[ToolAudit] 事件构建失败 toolId={}: {}", toolId, e.getMessage());
        }
    }

    private Map<String, String> scrubParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> scrubbed = new LinkedHashMap<>();
        params.forEach((k, v) -> scrubbed.put(k, desensitizeClient.scrub(v)));
        return scrubbed;
    }
}
