package com.sunshine.common.core.exception;

import lombok.Getter;

/** 业务异常 — 仅允许 {@link ErrorCode}，禁止散落字符串 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
