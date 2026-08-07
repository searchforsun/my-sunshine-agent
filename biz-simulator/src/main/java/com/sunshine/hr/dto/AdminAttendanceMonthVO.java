package com.sunshine.hr.dto;

import java.math.BigDecimal;

/** Admin CRUD：考勤月报（复合键 tenant+user+yearMonth）。 */
public record AdminAttendanceMonthVO(
        String tenantId,
        String userId,
        String yearMonth,
        Integer lateCount,
        BigDecimal overtimeHours,
        String frostLedgerSummary
) {
}
