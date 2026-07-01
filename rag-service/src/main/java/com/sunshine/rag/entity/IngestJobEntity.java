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
@Table(name = "ingest_job")
@Getter
@Setter
public class IngestJobEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;
    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;
    @Column(name = "doc_id", length = 128)
    private String docId;
    @Column(name = "file_name", length = 512)
    private String fileName;
    @Column(name = "mime_type", length = 128)
    private String mimeType;
    @Column(nullable = false, length = 24)
    private String status = "parsing";
    private Double confidence;
    @Column(name = "parsed_markdown", columnDefinition = "MEDIUMTEXT")
    private String parsedMarkdown;
    @Column(name = "error_msg", length = 1024)
    private String errorMsg;
    @Column(name = "auto_pass", nullable = false)
    private boolean autoPass;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
