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

    /**
     * L1 Admin 列表行：近→中→远；近/中各一行一轮（user+assistant），远窗一行总摘要。
     * {@code band}=near|mid|far；近/中 {@code index} 区内从新到旧 1..k。
     */
    public record L1WindowRowView(
            String band,
            int index,
            String userText,
            String assistantText,
            boolean assistantSummarized,
            Instant at) {
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
            Instant updatedAt,
            List<L1WindowRowView> rows) {
    }

    /**
     * L3 索引运维视图。Milvus 向量条数暂无 orchestrator 侧 count API，
     * 勿用 L2 状态计数冒充索引健康；{@code note} 说明限制。
     */
    public record L3StatusView(
            String userId,
            String tenantId,
            boolean contextEnabled,
            String collection,
            String note,
            long l1RowCount,
            int l3TopK,
            double l3MinScore) {
    }

    /**
     * L3 已索引 chunk。{@code expiresAt} 当前无硬过期字段，恒为 null（孤儿 GC 才删）。
     */
    public record L3EntryView(
            String msgId,
            String role,
            int chunkIndex,
            String content,
            Instant createdAt,
            Instant expiresAt) {
    }

    public record GcResultView(boolean ok, String message) {
    }

    public record ReingestResultView(String convId, int ingested, String message) {
    }

    /** Admin 右侧会话列表：id + 标题 */
    public record ConversationSummaryView(
            String id,
            String title,
            Instant createdAt,
            Instant updatedAt) {
    }
}
