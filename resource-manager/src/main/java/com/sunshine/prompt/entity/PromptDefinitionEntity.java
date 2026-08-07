package com.sunshine.prompt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "prompt_definition")
@Getter
@Setter
public class PromptDefinitionEntity {
    @Id
    @Column(length = 128)
    private String id;
    @Column(length = 32, nullable = false)
    private String kind;
    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;
    @Column(length = 512)
    private String description;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private int priority;
    @Column(name = "active_version", nullable = false)
    private int activeVersion;
    @Column(name = "catalog_version", nullable = false)
    private long catalogVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
