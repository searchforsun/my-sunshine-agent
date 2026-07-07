package com.sunshine.orchestrator.peer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "peer_run")
@Getter
@Setter
public class PeerRunEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "message_id", length = 64, nullable = false, unique = true)
    private String messageId;

    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "template_id", length = 128, nullable = false)
    private String templateId;

    @Column(name = "transcript_json", columnDefinition = "JSON", nullable = false)
    private String transcriptJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
