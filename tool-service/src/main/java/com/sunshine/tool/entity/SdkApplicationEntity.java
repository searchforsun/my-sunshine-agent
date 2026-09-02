package com.sunshine.tool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sdk_application")
@Getter
@Setter
public class SdkApplicationEntity {

    @Id
    private String id;

    @Column(name = "nacos_service", nullable = false, length = 128)
    private String nacosService;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "catalog_path", nullable = false, length = 256)
    private String catalogPath = "/sunshine/tools/catalog";

    @Column(name = "invoke_path", nullable = false, length = 256)
    private String invokePath = "/sunshine/tools/invoke";

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(nullable = false, length = 16)
    private String status = "offline";

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
