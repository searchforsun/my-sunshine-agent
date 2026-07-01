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
    @Column(name = "config_snapshot_json", columnDefinition = "JSON")
    private String configSnapshotJson;
    @Column(nullable = false, length = 16)
    private String status = "pending";
    @Column(name = "report_id")
    private Long reportId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "finished_at")
    private Instant finishedAt;
}
