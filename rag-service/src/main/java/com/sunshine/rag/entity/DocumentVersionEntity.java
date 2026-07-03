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
@Table(name = "document_version")
@Getter
@Setter
public class DocumentVersionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;
    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;
    @Column(name = "doc_id", nullable = false, length = 128)
    private String docId;
    @Column(nullable = false, length = 14)
    private String version;
    @Column(nullable = false, length = 16)
    private String status = "draft";
    @Column(name = "parsed_markdown", columnDefinition = "MEDIUMTEXT")
    private String parsedMarkdown;
    @Column(name = "storage_path", length = 512)
    private String storagePath;
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;
    @Column(name = "ingest_job_id")
    private Long ingestJobId;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
