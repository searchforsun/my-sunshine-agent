package com.sunshine.orchestrator.memory.mtm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "conversation_memory_mtm")
@Getter
@Setter
public class ConversationMemoryEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(name = "conv_id", nullable = false, length = 32, unique = true)
    private String convId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(length = 512)
    private String topics;

    @Column(length = 32)
    private String intent;

    @Column(name = "heat_score", nullable = false)
    private int heatScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
