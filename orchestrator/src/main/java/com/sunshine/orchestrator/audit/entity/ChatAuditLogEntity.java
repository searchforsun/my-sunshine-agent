package com.sunshine.orchestrator.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "chat_audit_log")
@Getter
@Setter
public class ChatAuditLogEntity {

    @Id
    private String id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String status;

    private String intent;

    @Column(name = "content_len", nullable = false)
    private int contentLen;

    @Column(columnDefinition = "JSON")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
