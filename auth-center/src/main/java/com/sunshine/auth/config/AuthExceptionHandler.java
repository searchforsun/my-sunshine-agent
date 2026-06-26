package com.sunshine.auth.config;

import cn.dev33.satoken.exception.NotLoginException;
import com.sunshine.auth.exception.AuthErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.common.web.ErrorCodeHttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<?>> handleNotLogin(NotLoginException ex) {
        return ResponseEntity.status(ErrorCodeHttpStatus.of(AuthErrorCode.TOKEN_INVALID))
                .body(R.fail(AuthErrorCode.TOKEN_INVALID));
    }
}
