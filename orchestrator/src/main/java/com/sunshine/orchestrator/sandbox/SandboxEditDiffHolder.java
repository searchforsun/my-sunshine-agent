package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.SandboxEditDiff;

import java.util.concurrent.ConcurrentHashMap;

/** edit 工具 meta.editDiff 暂存：execute 写入、PostActing Hook 消费 */
public final class SandboxEditDiffHolder {

    private static final ConcurrentHashMap<String, SandboxEditDiff> BY_TOOL_USE = new ConcurrentHashMap<>();

    private SandboxEditDiffHolder() {}

    public static void put(String toolUseId, SandboxEditDiff diff) {
        if (toolUseId == null || diff == null) {
            return;
        }
        BY_TOOL_USE.put(toolUseId, diff);
    }

    public static SandboxEditDiff take(String toolUseId) {
        if (toolUseId == null) {
            return null;
        }
        return BY_TOOL_USE.remove(toolUseId);
    }
}
