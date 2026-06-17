package com.sunshine.finance.dto;

import java.math.BigDecimal;

public record FinanceMessageVO(
        long id,
        String title,
        String type,
        String status,
        BigDecimal amount,
        String applicant,
        String createdAt
) {
}
