package com.sunshine.finance.dto;

import java.math.BigDecimal;

/** Admin 新建/更新报销单请求；userId 必填。 */
public record AdminExpenseRequest(
        String userId,
        String tenantId,
        String category,
        BigDecimal amount,
        String status,
        String occurredOn,
        String remark
) {
}
