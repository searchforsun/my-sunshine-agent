package com.sunshine.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "workflow_definition")
@Getter
@Setter
public class WorkflowDefinitionEntity {

    @EmbeddedId
    private WorkflowDefinitionId pk;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, length = 24)
    private String mode = "workflow";

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "active_version", nullable = false)
    private int activeVersion;

    @Column(nullable = false, length = 16)
    private String source = "studio";

    @Column(nullable = false, length = 16)
    private String kind = "all";

    @Column(length = 64)
    private String maintainer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String tenantId() {
        return pk != null ? pk.getTenantId() : "default";
    }

    public String workflowId() {
        return pk != null ? pk.getId() : null;
    }
}
