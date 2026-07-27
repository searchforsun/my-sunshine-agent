package com.sunshine.orchestrator.execution;

/** 节点显式输入绑定：参数名 -> 上游变量引用 + 类型 + 必填 */
public record InputBinding(
        String name,
        String source,
        VarType type,
        boolean required
) {
    public InputBinding {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("InputBinding.name 不可为空");
        }
        if (source == null) {
            source = "";
        }
        if (type == null) {
            type = VarType.STRING;
        }
    }

    /** 简化构造（默认 STRING + 非必填） */
    public InputBinding(String name, String source) {
        this(name, source, VarType.STRING, false);
    }
}
