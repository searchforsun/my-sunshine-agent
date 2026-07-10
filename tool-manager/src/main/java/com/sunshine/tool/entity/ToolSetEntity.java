package com.sunshine.tool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "tool_set")
@Getter
@Setter
public class ToolSetEntity {

    @Id
    private String id;

    @Column(name = "set_type", nullable = false, length = 32)
    private String setType;

    @Column(name = "tenant_id", length = 32)
    private String tenantId;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
