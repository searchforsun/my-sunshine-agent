package com.sunshine.hr.dto;

/** Admin 新建/更新请假单；userId 必填。 */
public record AdminLeaveRequestRequest(
        String userId,
        String tenantId,
        String leaveType,
        String startDate,
        String endDate,
        String reason,
        String status
) {
}
