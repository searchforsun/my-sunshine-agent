package com.sunshine.orchestrator.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 租户月用量配额（phase5 5.2.4）——orchestrator 管理，llm-gateway 请求前经 /api/usage/quota/check 校验。 */
@Entity
@Table(name = "tenant_quota")
@Getter
@Setter
public class TenantQuotaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "month_token_limit", nullable = false)
    private long monthTokenLimit;

    /** 模型白名单 JSON 数组，如 ["deepseek-v4-flash","qwen-plus"]；NULL=不限制 */
    @Column(name = "model_whitelist")
    private String modelWhitelist;

    @Column(nullable = false)
    private boolean enabled;

    private String remark;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
