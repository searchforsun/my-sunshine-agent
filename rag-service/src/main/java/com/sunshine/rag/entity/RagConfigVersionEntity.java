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
@Table(name = "rag_config_version")
@Getter
@Setter
public class RagConfigVersionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "bundle_id", nullable = false)
    private Long bundleId;
    @Column(name = "version_no", nullable = false)
    private int versionNo;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(name = "payload_json", nullable = false, columnDefinition = "JSON")
    private String payloadJson;
    @Column(name = "change_note", length = 512)
    private String changeNote;
    @Column(name = "created_by", length = 64)
    private String createdBy;
    @Column(name = "publish_eval_job_id")
    private Long publishEvalJobId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "published_at")
    private Instant publishedAt;
}
