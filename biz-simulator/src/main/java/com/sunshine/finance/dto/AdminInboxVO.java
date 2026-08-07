package com.sunshine.finance.dto;

import java.math.BigDecimal;

/** Admin CRUD：财务待办（含归属 userId）。 */
public record AdminInboxVO(
        String id,
        String tenantId,
        String userId,
        String title,
        String status,
        BigDecimal amount
) {
}
