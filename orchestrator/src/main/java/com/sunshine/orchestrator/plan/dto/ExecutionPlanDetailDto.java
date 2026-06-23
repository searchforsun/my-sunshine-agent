package com.sunshine.orchestrator.plan.dto;

import com.sunshine.orchestrator.plan.ExecutionPlanEntity;
import com.sunshine.orchestrator.plan.PlanJsonCodec;
import com.sunshine.orchestrator.plan.PlanNodeTrace;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Plan 详情（含 plan JSON 与 execution_trace） */
@Data
@Builder
public class ExecutionPlanDetailDto {

    private String id;
    private String conversationId;
    private String messageId;
    private String status;
    private String plannerModel;
    private String plannerReason;
    private String rejectReason;
    private Map<String, Object> plan;
    private Map<String, Object> validatedPlan;
    private List<PlanNodeTrace> nodes;
    private Instant createdAt;
    private Instant validatedAt;
    private Instant startedAt;
    private Instant completedAt;

    public static ExecutionPlanDetailDto from(ExecutionPlanEntity entity, PlanJsonCodec codec) {
        return ExecutionPlanDetailDto.builder()
                .id(entity.getId())
                .conversationId(entity.getConversationId())
                .messageId(entity.getMessageId())
                .status(entity.getStatus())
                .plannerModel(entity.getPlannerModel())
                .plannerReason(entity.getPlannerReason())
                .rejectReason(entity.getRejectReason())
                .plan(codec.parseJsonMap(entity.getPlanJson()))
                .validatedPlan(codec.parseJsonMap(entity.getValidatedJson()))
                .nodes(codec.traceFromJson(entity.getExecutionTrace()))
                .createdAt(entity.getCreatedAt())
                .validatedAt(entity.getValidatedAt())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }
}
