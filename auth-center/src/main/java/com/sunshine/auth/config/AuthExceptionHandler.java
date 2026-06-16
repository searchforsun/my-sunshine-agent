package com.sunshine.auth.config;

import cn.dev33.satoken.exception.NotLoginException;
import com.sunshine.common.core.result.R;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<R<?>> handleNotLogin(NotLoginException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail(401, "未登录或 Token 已失效"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<?>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(R.fail(400, msg));
    }
}
