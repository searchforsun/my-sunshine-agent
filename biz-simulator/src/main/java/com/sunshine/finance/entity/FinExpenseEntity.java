package com.sunshine.finance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "fin_expense")
@Getter
@Setter
public class FinExpenseEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(length = 512)
    private String remark;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
