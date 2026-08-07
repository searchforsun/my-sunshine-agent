package com.sunshine.hr.dto;

/** Admin CRUD：请假单（含归属 userId）。 */
public record AdminLeaveRequestVO(
        String id,
        String tenantId,
        String userId,
        String leaveType,
        String startDate,
        String endDate,
        String reason,
        String status
) {
}
