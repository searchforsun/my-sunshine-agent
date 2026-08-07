package com.sunshine.hr.dto;

import java.math.BigDecimal;

/** Admin CRUD：假期余额（复合键 tenant+user+year）。 */
public record AdminLeaveBalanceVO(
        String tenantId,
        String userId,
        Integer year,
        BigDecimal annual,
        BigDecimal qingsong,
        BigDecimal compensatory
) {
}
