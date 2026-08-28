package com.sunshine.orchestrator.processing;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具标量入参暂存：middleware 开步时写入、同线程收口时消费（无跨线程转移）。
 * 工具步落库的 StepMetadata 需要确定性入参（白名单标量值）渲染 schema 行，
 * 而 ToolResultEndEvent 不携带 ToolUseBlock.input，故经 holder 透传。
 */
public final class ToolArgsHolder {

    private static final ConcurrentHashMap<String, String> BY_TOOL_USE = new ConcurrentHashMap<>();

    private ToolArgsHolder() {}

    public static void put(String toolUseId, String keyArgs) {
        if (toolUseId == null || keyArgs == null || keyArgs.isBlank()) {
            return;
        }
        BY_TOOL_USE.put(toolUseId, keyArgs);
    }

    public static String take(String toolUseId) {
        if (toolUseId == null) {
            return null;
        }
        return BY_TOOL_USE.remove(toolUseId);
    }
}
