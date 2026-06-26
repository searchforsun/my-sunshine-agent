package com.sunshine.common.web;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.exception.ErrorCode;
import com.sunshine.common.core.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常 — HTTP 状态与 ErrorCode.code 对齐，body 统一 {@link R#fail(ErrorCode)} */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<?>> handleBizException(BizException e) {
        log.warn("业务异常: key={}, msg={}", e.getErrorCode().getKey(), e.getErrorCode().getMessage());
        return failResponse(e.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<?>> handleValidation(MethodArgumentNotValidException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return failResponse(CommonErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<?>> handleException(Exception e) {
        log.error("系统异常", e);
        return failResponse(CommonErrorCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<R<?>> failResponse(ErrorCode errorCode) {
        return ResponseEntity.status(ErrorCodeHttpStatus.of(errorCode)).body(R.fail(errorCode));
    }
}
