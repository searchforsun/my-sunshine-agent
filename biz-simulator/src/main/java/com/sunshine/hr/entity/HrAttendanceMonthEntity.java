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
@Table(name = "hr_attendance_month")
@IdClass(HrAttendanceMonthId.class)
@Getter
@Setter
public class HrAttendanceMonthEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Id
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(name = "late_count", nullable = false)
    private Integer lateCount = 0;

    @Column(name = "overtime_hours", nullable = false, precision = 8, scale = 1)
    private BigDecimal overtimeHours = BigDecimal.ZERO;

    @Column(name = "frost_ledger_summary", length = 512)
    private String frostLedgerSummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
