package com.sunshine.finance.model;

import java.math.BigDecimal;

/** 财务待办收件箱条目（归属当前用户）。 */
public record FinanceInboxItem(
        String id,
        String title,
        String status,
        BigDecimal amount
) {
}
