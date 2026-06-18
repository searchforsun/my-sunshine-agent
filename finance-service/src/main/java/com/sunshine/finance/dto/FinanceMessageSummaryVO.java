package com.sunshine.finance.dto;

import java.math.BigDecimal;

/** 按状态汇总的财务消息统计 */
public record FinanceMessageSummaryVO(
        String status,
        int count,
        BigDecimal totalAmount
) {
}
