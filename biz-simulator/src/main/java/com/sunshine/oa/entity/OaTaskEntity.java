package com.sunshine.oa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "oa_task")
@Getter
@Setter
public class OaTaskEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(name = "assignee_user_id", nullable = false, length = 64)
    private String assigneeUserId;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
