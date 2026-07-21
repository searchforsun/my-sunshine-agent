package com.sunshine.hr.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "hr_leave_balance")
@IdClass(HrLeaveBalanceId.class)
@Getter
@Setter
public class HrLeaveBalanceEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Id
    @Column(nullable = false)
    private Integer year;

    @Column(nullable = false, precision = 8, scale = 1)
    private BigDecimal annual = BigDecimal.ZERO;

    @Column(nullable = false, precision = 8, scale = 1)
    private BigDecimal qingsong = BigDecimal.ZERO;

    @Column(nullable = false, precision = 8, scale = 1)
    private BigDecimal compensatory = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
