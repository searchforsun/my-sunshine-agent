package com.sunshine.orchestrator.peer;

import java.time.Instant;

public record PeerRunAuditView(
        String messageId,
        String templateId,
        String transcriptJson,
        Instant updatedAt) {
}
