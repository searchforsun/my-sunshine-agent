package com.sunshine.rag.admin.config;

import com.sunshine.common.core.result.R;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.sunshine.rag.admin")
public class RagAdminExceptionHandler {

    @ExceptionHandler(ConfigVersionConflictException.class)
    public ResponseEntity<R<Void>> handleVersionConflict(ConfigVersionConflictException ex) {
        R<Void> response = new R<>();
        response.setCode(409);
        response.setMsg(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
}
