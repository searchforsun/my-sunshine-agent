package com.sunshine.hr.dto;

import java.math.BigDecimal;

/** Admin 新建/更新考勤月报；userId + yearMonth 必填。 */
public record AdminAttendanceMonthRequest(
        String userId,
        String tenantId,
        String yearMonth,
        Integer lateCount,
        BigDecimal overtimeHours,
        String frostLedgerSummary
) {
}
