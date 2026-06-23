package com.sunshine.orchestrator.plan.dto;

import com.sunshine.orchestrator.plan.ExecutionPlanEntity;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** 会话下 Plan 列表摘要 */
@Data
@Builder
public class ExecutionPlanSummaryDto {

    private String id;
    private String messageId;
    private String status;
    private String plannerReason;
    private Instant createdAt;
    private Instant completedAt;

    public static ExecutionPlanSummaryDto from(ExecutionPlanEntity entity) {
        return ExecutionPlanSummaryDto.builder()
                .id(entity.getId())
                .messageId(entity.getMessageId())
                .status(entity.getStatus())
                .plannerReason(entity.getPlannerReason())
                .createdAt(entity.getCreatedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }
}
