package com.sunshine.rag.admin.config;

import com.sunshine.common.core.result.R;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(basePackages = "com.sunshine.rag.admin")
public class RagAdminExceptionHandler {

    @ExceptionHandler(PublishGateException.class)
    public ResponseEntity<R<Map<String, Object>>> handlePublishGate(PublishGateException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recallAt5", ex.evalResult().recallAt5());
        body.put("baselineRecallAt5", ex.evalResult().baselineRecallAt5());
        body.put("failedSamples", ex.evalResult().failedSamples());
        R<Map<String, Object>> response = new R<>();
        response.setCode(422);
        response.setMsg("publish gate failed");
        response.setData(body);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
    }

    @ExceptionHandler(ConfigVersionConflictException.class)
    public ResponseEntity<R<Void>> handleVersionConflict(ConfigVersionConflictException ex) {
        R<Void> response = new R<>();
        response.setCode(409);
        response.setMsg(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
}
