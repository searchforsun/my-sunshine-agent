package com.sunshine.orchestrator.taskboard;

import java.time.Instant;
import java.util.List;

/** GET /api/audit/taskboard/{messageId} 响应 */
public record TaskBoardAuditView(
        String boardId,
        String messageId,
        String conversationId,
        String tenantId,
        String userId,
        int revision,
        List<TaskBoardItemView> items,
        Instant createdAt,
        Instant updatedAt) {
}
