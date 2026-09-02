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
@Table(name = "model_scene_binding")
@Getter
@Setter
public class ModelSceneBindingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "scene_key", length = 64, nullable = false)
    private String sceneKey;
    @Column(name = "primary_model", length = 128, nullable = false)
    private String primaryModel;
    @Column(name = "fallback_model", length = 128)
    private String fallbackModel;
    /** 场景专属参数 JSON */
    @Column(columnDefinition = "json")
    private String extras;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";
    @Column(length = 256)
    private String remark;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
