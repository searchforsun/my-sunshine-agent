package com.sunshine.chatimage.controller;

import com.sunshine.chatimage.exception.UploadErrorCode;
import com.sunshine.common.core.result.R;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * multipart 解析异常在 handler mapping 前抛出（handler 为 null），无法按路由区分业务域；
 * spring.servlet.multipart 上限是服务级共享约束（skill zip 与聊天图片共用），文案与 errorKey 亦须通用。
 */
@RestControllerAdvice
public class ChatImageUploadAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> onMultipartOverflow(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(UploadErrorCode.TOO_LARGE.getCode())
                .body(R.fail(UploadErrorCode.TOO_LARGE));
    }
}
