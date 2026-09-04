package com.sunshine.chatimage.controller;

import com.sunshine.chatimage.exception.ChatImageErrorCode;
import com.sunshine.common.core.result.R;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** multipart 解析失败发生在 handler mapping 之前，controller 内 @ExceptionHandler 不可达，须经 advice 兜住 */
@RestControllerAdvice
public class ChatImageUploadAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> onMultipartOverflow(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(ChatImageErrorCode.IMAGE_TOO_LARGE.getCode())
                .body(R.fail(ChatImageErrorCode.IMAGE_TOO_LARGE));
    }
}
