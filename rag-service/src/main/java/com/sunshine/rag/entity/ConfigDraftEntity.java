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
@Table(name = "config_draft")
@Getter
@Setter
public class ConfigDraftEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;
    @Column(nullable = false, length = 64)
    private String scope;
    @Column(name = "payload_json", nullable = false, columnDefinition = "JSON")
    private String payloadJson;
    @Column(nullable = false, length = 16)
    private String status = "draft";
    @Column(name = "created_by", length = 64)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "published_at")
    private Instant publishedAt;
}
