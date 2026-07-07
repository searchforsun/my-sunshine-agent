package com.sunshine.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "workflow_version")
@Getter
@Setter
public class WorkflowVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 16)
    private String status = "draft";

    @Column(name = "plan_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String planJson;

    @Column(name = "catalog_meta", columnDefinition = "JSON")
    private String catalogMeta;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
