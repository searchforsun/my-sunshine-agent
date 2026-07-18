package com.sunshine.orchestrator.sandbox;

import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 沙箱工具取消时写入时间线展开区的快照（exec 命令 / grep·glob pattern）。
 * Controller 即时 pause 单写用；禁止在 Tool unwind 再 pause。
 */
public final class SandboxCancelExpand {

    private SandboxCancelExpand() {
    }

    public static String detail(String toolName, Map<String, Object> body) {
        if (!StringUtils.hasText(toolName) || body == null || body.isEmpty()) {
            return null;
        }
        if (SandboxIds.EXEC.equals(toolName)) {
            Object cmd = body.get("command");
            return cmd != null && StringUtils.hasText(String.valueOf(cmd))
                    ? String.valueOf(cmd).strip() : null;
        }
        if (SandboxIds.GREP.equals(toolName) || SandboxIds.GLOB.equals(toolName)) {
            Object pattern = body.get("pattern");
            return pattern != null && StringUtils.hasText(String.valueOf(pattern))
                    ? String.valueOf(pattern).strip() : null;
        }
        return null;
    }
}
