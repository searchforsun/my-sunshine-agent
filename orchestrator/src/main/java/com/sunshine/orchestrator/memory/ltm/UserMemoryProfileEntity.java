package com.sunshine.orchestrator.memory.ltm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "user_memory_profile")
@Getter
@Setter
public class UserMemoryProfileEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(length = 128)
    private String department;

    @Column(name = "role_label", length = 128)
    private String roleLabel;

    @Column(columnDefinition = "TEXT")
    private String preferences;

    @Column(name = "stable_facts", columnDefinition = "TEXT")
    private String stableFacts;

    @Column(length = 512)
    private String permissions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
