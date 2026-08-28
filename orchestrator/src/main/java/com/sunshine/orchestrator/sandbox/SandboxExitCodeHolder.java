package com.sunshine.orchestrator.sandbox;

import java.util.concurrent.ConcurrentHashMap;

/**
 * exec 退出码暂存：execute 写入、工具步收口消费。
 * exitCode 不随 stdout 原文返回（保持模型可见结果不变），经 holder 透传进
 * StepMetadata.toolExitCode，供确定性 schema 行渲染（五层 §5.5.8 / task-scene §6.5）。
 */
public final class SandboxExitCodeHolder {

    private static final ConcurrentHashMap<String, Integer> BY_TOOL_USE = new ConcurrentHashMap<>();

    private SandboxExitCodeHolder() {}

    public static void put(String toolUseId, Integer exitCode) {
        if (toolUseId == null || exitCode == null) {
            return;
        }
        BY_TOOL_USE.put(toolUseId, exitCode);
    }

    public static Integer take(String toolUseId) {
        if (toolUseId == null) {
            return null;
        }
        return BY_TOOL_USE.remove(toolUseId);
    }
}
