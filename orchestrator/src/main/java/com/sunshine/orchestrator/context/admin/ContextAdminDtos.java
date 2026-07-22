package com.sunshine.orchestrator.context.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Admin API 请求/响应 DTO。 */
public final class ContextAdminDtos {

    private ContextAdminDtos() {
    }

    public record L2StateView(
            String id,
            String userId,
            String tenantId,
            String kind,
            String stateKey,
            String stateValue,
            double confidence,
            String status,
            Instant expiresAt,
            String sourceMsgId,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record L2UpdateRequest(String stateValue, Double confidence, String status) {
    }

    public record L1SnapshotView(
            String convId,
            String userId,
            String tenantId,
            Map<String, String> midAnswers,
            String farSummary,
            List<String> farFoldedMsgIds,
            int nearN,
            int midN,
            Instant updatedAt) {
    }

    public record L3StatusView(
            String userId,
            String tenantId,
            boolean contextEnabled,
            String collection,
            long activeL2Count,
            long voidL2Count,
            long supersededL2Count,
            long l1ConversationCount,
            int l3TopK,
            double l3MinScore) {
    }

    public record GcResultView(boolean ok, String message) {
    }

    public record ReingestResultView(String convId, int ingested, String message) {
    }
}
