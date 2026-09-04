package com.sunshine.chatimage.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 聊天图片上传业务错误码 */
@Getter
@RequiredArgsConstructor
public enum ChatImageErrorCode implements ErrorCode {
    IMAGE_REQUIRED(400, "chat_image_required", "图片不能为空"),
    IMAGE_TYPE_UNSUPPORTED(400, "chat_image_type_unsupported", "不支持的图片类型，仅允许 .png,.jpg,.jpeg,.webp,.gif"),
    IMAGE_TOO_LARGE(400, "chat_image_too_large", "图片超过大小限制 10MB"),
    UPLOAD_FAILED(500, "chat_image_upload_failed", "图片上传失败，请重试");

    private final int code;
    private final String key;
    private final String message;
}
