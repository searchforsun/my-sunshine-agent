package com.sunshine.orchestrator.biz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 业务任务板权威态（authority §4.1）：跨会话流程状态，按用户 + 活跃状态 + 时间窗 +
 * biz_scene 召回；外部 OA/工单仅挂 {@code external_ticket_ref} 指针。
 * 与 agent 执行态（fast 会话级任务清单 / H1 / KV todo）边界隔离，不双写。
 */
@Entity
@Table(name = "business_task")
@Getter
@Setter
public class BusinessTaskEntity {

    @Id
    @Column(name = "task_id", length = 32)
    private String taskId;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "biz_scene", nullable = false, length = 64)
    private String bizScene;

    /** pending | running | awaiting_confirm | done | archived | failed */
    @Column(nullable = false, length = 24)
    private String status = "pending";

    @Column(nullable = false, length = 128)
    private String title;

    /** 当前步骤骨架（结构化，非散文全文）。 */
    @Column(name = "steps_json", columnDefinition = "TEXT")
    private String stepsJson;

    /** 待确认项（HITL / request_decision 联动）。 */
    @Column(name = "pending_confirmations_json", columnDefinition = "TEXT")
    private String pendingConfirmationsJson;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column
    private Instant deadline;

    /** low | medium | high */
    @Column(name = "risk_level", length = 16)
    private String riskLevel = "low";

    /** 外系统工单/审批单号指针（权威态仅指针，不含原文）。 */
    @Column(name = "external_ticket_ref", length = 128)
    private String externalTicketRef;

    /** 证据指针列表（OSS key / messageId / ticket），不含原文。 */
    @Column(name = "evidence_refs_json", columnDefinition = "TEXT")
    private String evidenceRefsJson;

    /** 最近关联会话。 */
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
