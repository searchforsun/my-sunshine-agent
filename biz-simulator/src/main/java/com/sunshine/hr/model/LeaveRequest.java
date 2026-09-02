package com.sunshine.hr.model;

/** 用户请假单（归属校验按 tenant + user）。 */
public record LeaveRequest(
        String id,
        String leaveType,
        String startDate,
        String endDate,
        String reason,
        String status
) {
}
