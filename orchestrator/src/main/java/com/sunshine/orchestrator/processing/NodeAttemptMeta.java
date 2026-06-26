package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.execution.retry.NodeRetryExecutor;

import java.util.List;

/** Workflow 节点执行 attempt — 经 SSE metadata 实时下发 */
public record NodeAttemptMeta(
        int attemptNo,
        String status,
        String errorClass,
        String summary,
        Long startedAt,
        Long endedAt) {

    public static NodeAttemptMeta fromRecord(NodeRetryExecutor.PlanNodeAttemptRecord record) {
        return new NodeAttemptMeta(
                record.attemptNo(),
                record.status(),
                record.errorClass(),
                record.summary(),
                record.startedAt(),
                record.endedAt());
    }

    public static List<NodeAttemptMeta> fromRecords(List<NodeRetryExecutor.PlanNodeAttemptRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream().map(NodeAttemptMeta::fromRecord).toList();
    }
}
