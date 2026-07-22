package com.sunshine.orchestrator.context.l1;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** L1 会话派生：mid 答案映射 + far 摘要 + 窗口元数据 */
@Entity
@Table(name = "conversation_context_l1")
@Getter
@Setter
public class ConversationContextL1Entity {

    @Id
    @Column(name = "conv_id", length = 32)
    private String convId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    /** JSON map msgId -> answer summary */
    @Column(name = "mid_answers", columnDefinition = "MEDIUMTEXT")
    private String midAnswers;

    @Column(name = "far_summary", columnDefinition = "MEDIUMTEXT")
    private String farSummary;

    @Column(name = "near_n", nullable = false)
    private int nearN = 8;

    @Column(name = "mid_n", nullable = false)
    private int midN = 8;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
