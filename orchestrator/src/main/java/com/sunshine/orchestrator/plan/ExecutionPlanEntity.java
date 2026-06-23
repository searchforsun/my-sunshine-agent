package com.sunshine.orchestrator.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 动态 Plan 落库实体 */
@Entity
@Table(name = "execution_plan")
@Getter
@Setter
public class ExecutionPlanEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(name = "message_id", nullable = false, length = 64)
    private String messageId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "planner_model", length = 64)
    private String plannerModel;

    @Column(name = "planner_reason", length = 512)
    private String plannerReason;

    @Column(name = "plan_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String planJson;

    @Column(name = "validated_json", columnDefinition = "MEDIUMTEXT")
    private String validatedJson;

    @Column(name = "execution_trace", columnDefinition = "MEDIUMTEXT")
    private String executionTrace;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "reject_reason", length = 512)
    private String rejectReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "validated_at")
    private Instant validatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
