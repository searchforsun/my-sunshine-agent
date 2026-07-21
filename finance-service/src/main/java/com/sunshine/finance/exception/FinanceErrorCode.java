package com.sunshine.finance.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** finance-service 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum FinanceErrorCode implements ErrorCode {

    USER_REQUIRED(400, "finance_user_required", "缺少 x-user-id"),
    INVALID_EXPENSE_REQUEST(400, "finance_invalid_expense", "报销提交参数不完整"),
    INVALID_INBOX_REQUEST(400, "finance_invalid_inbox", "财务待办参数不完整"),
    EXPENSE_NOT_FOUND(404, "finance_expense_not_found", "报销单不存在"),
    INBOX_ITEM_NOT_FOUND(404, "finance_inbox_item_not_found", "财务待办不存在");

    private final int code;
    private final String key;
    private final String message;
}
