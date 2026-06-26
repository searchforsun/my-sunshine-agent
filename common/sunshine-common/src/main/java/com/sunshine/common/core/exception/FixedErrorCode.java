package com.sunshine.common.core.exception;

/** 下游 R 响应透传（如 BFF 转发 orchestrator 错误），msg 已由源服务定义 */
public record FixedErrorCode(int code, String key, String message) implements ErrorCode {

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
