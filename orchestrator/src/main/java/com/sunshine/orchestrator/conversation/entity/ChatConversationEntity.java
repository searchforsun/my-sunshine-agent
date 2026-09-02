package com.sunshine.orchestrator.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_conversation")
@Getter
@Setter
public class ChatConversationEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

    @Column(nullable = false, length = 128)
    private String title = "新对话";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 本会话最近一次发送时的执行模式（列名保留；取值 fast|pro|workflow） */
    @Column(name = "execution_preference", length = 32)
    private String executionPreference;

    /** 本会话绑定的知识库 id */
    @Column(name = "kb_id", length = 64)
    private String kbId;

    /** 会话类型：chat / task（task 绑定工作区） */
    @Column(length = 16)
    private String kind = "chat";

    /** kind=task 时绑定的工作区 id */
    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    /** 用户选定的 checkout 路径 */
    @Column(name = "checkout_path", length = 256)
    private String checkoutPath;

    /** 会话绑定模型（注册表 model_name；空则走 chat/default scene） */
    @Column(name = "model_name", length = 128)
    private String modelName;
}
