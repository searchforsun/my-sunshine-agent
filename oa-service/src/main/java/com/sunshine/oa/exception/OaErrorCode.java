package com.sunshine.oa.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** oa-service 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum OaErrorCode implements ErrorCode {

    USER_REQUIRED(400, "oa_user_required", "缺少 x-user-id"),
    TASK_NOT_FOUND(404, "oa_task_not_found", "OA 待办不存在或无权操作");

    private final int code;
    private final String key;
    private final String message;
}
