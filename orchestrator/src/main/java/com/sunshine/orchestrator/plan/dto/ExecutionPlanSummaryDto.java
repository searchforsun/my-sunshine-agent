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
    private Instant createdAt;
    private Instant completedAt;

    public static ExecutionPlanSummaryDto from(ExecutionPlanEntity entity) {
        return ExecutionPlanSummaryDto.builder()
                .id(entity.getId())
                .messageId(entity.getMessageId())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }
}
