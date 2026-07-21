package com.sunshine.hr.dto;

import java.math.BigDecimal;

/** Admin 新建/更新假期余额；userId + year 必填。 */
public record AdminLeaveBalanceRequest(
        String userId,
        String tenantId,
        Integer year,
        BigDecimal annual,
        BigDecimal qingsong,
        BigDecimal compensatory
) {
}
