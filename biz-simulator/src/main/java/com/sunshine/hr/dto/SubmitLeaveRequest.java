package com.sunshine.hr.dto;

public record SubmitLeaveRequest(
        String leaveType,
        String startDate,
        String endDate,
        String reason
) {
}
