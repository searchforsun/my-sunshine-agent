package com.sunshine.orchestrator.util;

import com.sunshine.common.core.exception.BizException;

/** 流式失败时面向用户的错误文案 */
public final class StreamErrorMessages {

    private StreamErrorMessages() {
    }

    public static String resolve(Throwable error) {
        if (error == null) {
            return "生成失败，请稍后重试";
        }
        if (error instanceof BizException biz) {
            return biz.getErrorCode().getMessage();
        }
        String msg = error.getMessage();
        if (msg != null && !msg.isBlank()) {
            return msg.strip();
        }
        return "生成失败，请稍后重试";
    }
}
