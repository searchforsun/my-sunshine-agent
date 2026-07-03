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
@Table(name = "eval_suite")
@Getter
@Setter
public class EvalSuiteEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;
    @Column(name = "suite_key", nullable = false, length = 64)
    private String suiteKey;
    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;
    @Column(length = 512)
    private String description;
    @Column(nullable = false, length = 16)
    private String kind = "standard";
    @Column(nullable = false, length = 8)
    private String format = "json";
    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;
    @Column(nullable = false, length = 16)
    private String storage = "mysql";
    @Column(name = "content_ref", length = 512)
    private String contentRef;
    @Column(name = "hooks_json", columnDefinition = "JSON")
    private String hooksJson;
    @Column(name = "config_json", columnDefinition = "JSON")
    private String configJson;
    @Column(name = "item_count", nullable = false)
    private int itemCount;
    @Column(nullable = false, length = 16)
    private String status = "active";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
