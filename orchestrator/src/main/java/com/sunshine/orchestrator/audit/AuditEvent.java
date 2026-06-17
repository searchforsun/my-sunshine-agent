package com.sunshine.orchestrator.audit;

import java.time.Instant;

public record AuditEvent(
        String id,
        String conversationId,
        String messageId,
        String userId,
        String tenantId,
        String eventType,
        String status,
        String intent,
        int contentLen,
        String payloadJson,
        Instant createdAt
) {
}
