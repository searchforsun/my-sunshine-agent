package com.sunshine.common.core.result;

import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/** 统一响应体 — 失败仅通过 {@link ErrorCode} 构造 */
@Data
@Schema(description = "统一响应")
public class R<T> implements Serializable {

    @Schema(description = "状态码")
    private int code;

    @Schema(description = "面向用户的消息")
    private String msg;

    @Schema(description = "稳定错误键")
    private String errorKey;

    @Schema(description = "数据")
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = CommonErrorCode.SUCCESS.getCode();
        r.msg = CommonErrorCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        R<T> r = new R<>();
        r.code = errorCode.getCode();
        r.msg = errorCode.getMessage();
        r.errorKey = errorCode.getKey();
        return r;
    }
}
