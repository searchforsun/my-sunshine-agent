package com.sunshine.common.core.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 跨模块通用错误码 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    SUCCESS(200, "success", "success"),
    BAD_REQUEST(400, "bad_request", "请求参数有误"),
    UNAUTHORIZED(401, "unauthorized", "未登录或登录已失效"),
    FORBIDDEN(403, "forbidden", "暂无权限执行此操作"),
    NOT_FOUND(404, "not_found", "请求的内容不存在"),
    GONE(410, "gone", "资源已不可用"),
    CONFLICT(409, "conflict", "数据冲突，请刷新后重试"),
    TOO_MANY_REQUESTS(429, "too_many_requests", "操作过于频繁，请稍后再试"),
    INTERNAL_ERROR(500, "internal_error", "系统繁忙，请稍后重试"),
    SERVICE_UNAVAILABLE(503, "service_unavailable", "服务暂时不可用，请稍后重试");

    private final int code;
    private final String key;
    private final String message;
}
