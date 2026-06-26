package com.sunshine.common.core.exception;

/**
 * 业务错误码契约：code 与 HTTP 语义对齐，msg 为面向用户的固定文案（SSOT）。
 */
public interface ErrorCode {

    /** HTTP 语义状态码，成功固定 200 */
    int getCode();

    /** 稳定机器可读键，供日志/前端可选映射 */
    String getKey();

    /** 面向用户的中文提示，禁止拼接内部 ID/堆栈 */
    String getMessage();
}
