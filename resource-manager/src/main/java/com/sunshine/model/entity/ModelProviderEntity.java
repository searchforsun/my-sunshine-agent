package com.sunshine.model.entity;

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
@Table(name = "model_provider")
@Getter
@Setter
public class ModelProviderEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "provider_key", length = 64, nullable = false)
    private String providerKey;
    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;
    @Column(length = 32, nullable = false)
    private String protocol = "openai-compatible";
    @Column(name = "base_url", length = 256, nullable = false)
    private String baseUrl;
    @Column(name = "path_prefix", length = 32, nullable = false)
    private String pathPrefix = "";
    @Column(name = "api_key_enc", length = 1024, nullable = false)
    private String apiKeyEnc;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
