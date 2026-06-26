package com.sunshine.finance.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** finance-service 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum FinanceErrorCode implements ErrorCode {

    MESSAGE_NOT_FOUND(404, "finance_message_not_found", "财务消息不存在");

    private final int code;
    private final String key;
    private final String message;
}
