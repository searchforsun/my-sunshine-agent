package com.sunshine.orchestrator.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_message")
@Getter
@Setter
public class ChatMessageEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 64)
    private String conversationId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String reasoning;

    /** 处理流水线步骤 JSON 数组 */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String steps;

    /** ReAct 正文分段 JSON 数组 */
    @Column(name = "content_blocks", columnDefinition = "MEDIUMTEXT")
    private String contentBlocks;

    @Column(nullable = false, length = 16)
    private String status = "completed";

    @Column(length = 32)
    private String intent;

    @Column(name = "execution_mode", length = 16)
    private String executionMode;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "execution_plan_id", length = 36)
    private String executionPlanId;

    /** user 消息发送时用户选择的 executionPreference */
    @Column(name = "execution_preference", length = 32)
    private String executionPreference;

    @Column(name = "resume_count", nullable = false)
    private int resumeCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
