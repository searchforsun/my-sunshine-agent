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

/** LLM 调用用量记录（phase5 5.2）——MQ 消费落库，维度字段为链路透传预留。 */
@Entity
@Table(name = "llm_usage_record")
@Getter
@Setter
public class LlmUsageRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "user_id")
    private String userId;

    @Column(nullable = false)
    private String model;

    @Column(name = "call_site")
    private String callSite;

    @Column(name = "run_id")
    private String runId;

    @Column(name = "round_id")
    private String roundId;

    @Column(nullable = false)
    private boolean stream;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(nullable = false)
    private boolean estimated;

    @Column(name = "request_at", nullable = false)
    private Instant requestAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
