package com.sunshine.finance.dto;

import java.math.BigDecimal;

public record SubmitExpenseRequest(
        String category,
        BigDecimal amount,
        String occurredOn,
        String remark
) {
}
