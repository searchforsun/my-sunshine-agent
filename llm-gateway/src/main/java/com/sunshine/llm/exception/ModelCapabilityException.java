package com.sunshine.llm.exception;

import lombok.Getter;

/**
 * 模型能力不满足请求（多模态 / 工具调用）。
 * <p>message 保持稳定错误码（{@link #code}），供 ModelRouter 能力判定与测试匹配；
 * 模型名单独携带，供错误出口组装面向用户的友好文案。
 */
@Getter
public class ModelCapabilityException extends IllegalArgumentException {

    private final String code;
    private final String modelName;

    public ModelCapabilityException(String code, String modelName) {
        super(code);
        this.code = code;
        this.modelName = modelName;
    }
}
