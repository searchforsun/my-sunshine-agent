package com.sunshine.finance.dto;

import java.math.BigDecimal;

/** 按状态汇总的报销统计 */
public record ExpenseSummaryVO(
        String status,
        int count,
        BigDecimal totalAmount
) {
}
