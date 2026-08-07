package com.sunshine.finance.model;

import java.math.BigDecimal;

/** 用户报销单（归属校验按 tenant + user）。 */
public record ExpenseRecord(
        String id,
        String category,
        BigDecimal amount,
        String status,
        String occurredOn,
        String remark
) {
}
