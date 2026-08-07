package com.sunshine.finance.dto;

import java.math.BigDecimal;

/** Admin CRUD：报销单（含归属 userId）。 */
public record AdminExpenseVO(
        String id,
        String tenantId,
        String userId,
        String category,
        BigDecimal amount,
        String status,
        String occurredOn,
        String remark
) {
}
