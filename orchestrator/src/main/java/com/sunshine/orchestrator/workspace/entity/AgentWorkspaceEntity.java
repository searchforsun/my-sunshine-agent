package com.sunshine.orchestrator.workspace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "agent_workspace")
@Getter
@Setter
public class AgentWorkspaceEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(name = "repo_url", length = 512, nullable = false)
    private String repoUrl;

    @Column(name = "repo_branch", length = 128, nullable = false)
    private String repoBranch = "main";

    @Column(name = "sandbox_profile", length = 32)
    private String sandboxProfile = "full";

    @Column(name = "memory_mb")
    private int memoryMb = 2048;

    @Column(precision = 3, scale = 1)
    private BigDecimal cpus = new BigDecimal("2.0");

    @Column(length = 128)
    private String image = "sunshine-sandbox-full:latest";

    @Column(length = 16)
    private String status = "active";

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
