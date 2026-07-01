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
@Table(name = "badcase")
@Getter
@Setter
public class BadcaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;
    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId = "default";
    @Column(nullable = false, length = 512)
    private String query;
    @Column(name = "relevant_doc_ids_json", columnDefinition = "JSON")
    private String relevantDocIdsJson;
    private String notes;
    private String source;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
