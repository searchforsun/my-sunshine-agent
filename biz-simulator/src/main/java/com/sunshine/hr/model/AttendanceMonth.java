package com.sunshine.hr.model;

/** 用户月度考勤摘要（含霜降台账）。 */
public record AttendanceMonth(
        String yearMonth,
        int lateCount,
        double overtimeHours,
        String frostLedgerSummary
) {
}
