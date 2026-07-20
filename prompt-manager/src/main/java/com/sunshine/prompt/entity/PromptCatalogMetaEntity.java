package com.sunshine.prompt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "prompt_catalog_meta")
@Getter
@Setter
public class PromptCatalogMetaEntity {
    @Id
    private Byte id;
    @Column(name = "catalog_version", nullable = false)
    private long catalogVersion;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
