package com.sunshine.orchestrator.usage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** LLM 用量日聚合（phase5 5.2.3）——聚合任务 upsert 写入，供 /ops 用量页排行与趋势。 */
@Entity
@Table(name = "llm_usage_daily")
@Getter
@Setter
public class LlmUsageDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String model;

    @Column(name = "call_site")
    private String callSite;

    @Column(nullable = false)
    private int calls;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "est_cost", nullable = false, precision = 14, scale = 6)
    private BigDecimal estCost;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
