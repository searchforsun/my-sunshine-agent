package com.sunshine.common.web;

import com.sunshine.common.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** ErrorCode.code → HTTP 状态（与 body.code 对齐） */
public final class ErrorCodeHttpStatus {

    private ErrorCodeHttpStatus() {
    }

    public static HttpStatus of(ErrorCode errorCode) {
        int code = errorCode.getCode();
        if (code == HttpStatus.OK.value()) {
            return HttpStatus.OK;
        }
        try {
            return HttpStatus.valueOf(code);
        } catch (IllegalArgumentException ex) {
            if (code >= 500) {
                return HttpStatus.INTERNAL_SERVER_ERROR;
            }
            if (code >= 400) {
                return HttpStatus.BAD_REQUEST;
            }
            return HttpStatus.OK;
        }
    }
}
