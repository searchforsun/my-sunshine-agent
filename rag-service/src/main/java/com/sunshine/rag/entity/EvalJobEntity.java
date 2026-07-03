package com.sunshine.rag.entity;

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
@Table(name = "eval_job")
@Getter
@Setter
public class EvalJobEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;
    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId = "default";
    @Column(nullable = false, length = 32)
    private String suite;
    @Column(name = "suite_id")
    private Long suiteId;
    @Column(name = "config_snapshot_json", columnDefinition = "JSON")
    private String configSnapshotJson;
    @Column(name = "config_version_id")
    private Long configVersionId;
    @Column(name = "config_mode", length = 16)
    private String configMode;
    @Column(nullable = false, length = 16)
    private String status = "pending";
    @Column(name = "total_items")
    private Integer totalItems;
    @Column(name = "processed_items", nullable = false)
    private int processedItems;
    @Column(name = "report_id")
    private Long reportId;
    @Column(name = "report_object_key", length = 512)
    private String reportObjectKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "finished_at")
    private Instant finishedAt;
}
