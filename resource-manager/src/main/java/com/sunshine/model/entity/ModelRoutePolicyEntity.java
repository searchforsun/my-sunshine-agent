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

/** 模型路由策略（phase5 5.3）：call_site → 候选模型池。 */
@Entity
@Table(name = "model_route_policy")
@Getter
@Setter
public class ModelRoutePolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "call_site", nullable = false, length = 64)
    private String callSite;

    @Column(nullable = false, columnDefinition = "JSON")
    private String models;

    @Column(nullable = false, length = 32)
    private String strategy;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(length = 256)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
