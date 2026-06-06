package com.sunshine.common.core.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体
 */
@Data
@Schema(description = "统一响应")
public class R<T> implements Serializable {

    @Schema(description = "状态码")
    private int code;

    @Schema(description = "消息")
    private String msg;

    @Schema(description = "数据")
    private T data;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.msg = "success";
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.code = code;
        r.msg = msg;
        return r;
    }

    public static <T> R<T> fail(String msg) {
        return fail(500, msg);
    }
}
