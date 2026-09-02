package com.sunshine.orchestrator.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

@Entity
@Table(name = "chat_message")
@Getter
@Setter
@DynamicUpdate
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

    /** 消息级 LLM usage + 上下文分组快照 JSON */
    @Column(name = "usage_json", columnDefinition = "MEDIUMTEXT")
    private String usageJson;

    @Column(nullable = false, length = 16)
    private String status = "completed";

    @Column(length = 32)
    private String intent;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "execution_plan_id", length = 36)
    private String executionPlanId;

    /** 本轮已触发 skill 集（逗号分隔；skill-sticky S-0） */
    @Column(name = "routing_skill_ids", columnDefinition = "MEDIUMTEXT")
    private String routingSkillIds;

    /** 本轮可调度 agent 集（逗号分隔；skill-sticky S-0） */
    @Column(name = "routing_agent_ids", columnDefinition = "MEDIUMTEXT")
    private String routingAgentIds;

    /** user 消息发送时的执行模式（列名保留；取值 fast|pro|workflow） */
    @Column(name = "execution_preference", length = 32)
    private String executionPreference;

    @Column(name = "resume_count", nullable = false)
    private int resumeCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
