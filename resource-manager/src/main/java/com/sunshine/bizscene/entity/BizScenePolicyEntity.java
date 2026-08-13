package com.sunshine.bizscene.entity;

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
@Table(name = "biz_scene_policy")
@Getter
@Setter
public class BizScenePolicyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(name = "biz_scene", nullable = false, length = 64)
    private String bizScene;

    @Column(nullable = false)
    private int version = 1;

    @Column(nullable = false, length = 16)
    private String status = "active";

    @Column(name = "rules_json", nullable = false, columnDefinition = "TEXT")
    private String rulesJson = "{}";

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
