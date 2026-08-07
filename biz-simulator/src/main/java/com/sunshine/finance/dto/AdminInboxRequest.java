package com.sunshine.finance.dto;

import java.math.BigDecimal;

/** Admin 新建/更新财务待办请求；userId 必填。 */
public record AdminInboxRequest(
        String userId,
        String tenantId,
        String title,
        String status,
        BigDecimal amount
) {
}
