package com.sunshine.tool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "mcp_server")
@Getter
@Setter
public class McpServerEntity {

    @Id
    private String id;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(nullable = false, length = 16)
    private String transport;

    @Column(length = 512)
    private String command;

    @Column(name = "args_json")
    private String argsJson;

    @Column(length = 512)
    private String endpoint;

    @Column(name = "env_json")
    private String envJson;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_probe_at")
    private Instant lastProbeAt;

    @Column(name = "probe_status", length = 16)
    private String probeStatus;

    @Column(name = "probe_error", length = 512)
    private String probeError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
