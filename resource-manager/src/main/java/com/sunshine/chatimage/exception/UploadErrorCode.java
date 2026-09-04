package com.sunshine.chatimage.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 服务级上传约束错误码（skill zip 与聊天图片共享同一 multipart 上限） */
@Getter
@RequiredArgsConstructor
public enum UploadErrorCode implements ErrorCode {
    TOO_LARGE(400, "upload_too_large", "上传文件超过大小限制（上限 10MB）");

    private final int code;
    private final String key;
    private final String message;
}
