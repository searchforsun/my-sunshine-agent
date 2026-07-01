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
@Table(name = "kb_config_override")
@Getter
@Setter
public class KbConfigOverrideEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;
    @Column(name = "kb_id", nullable = false, length = 64)
    private String kbId;
    @Column(name = "override_json", nullable = false, columnDefinition = "JSON")
    private String overrideJson;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
