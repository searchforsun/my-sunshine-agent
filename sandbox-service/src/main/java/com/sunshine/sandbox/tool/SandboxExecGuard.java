package com.sunshine.sandbox.tool;

import java.util.List;
import java.util.regex.Pattern;

/**
 * sandbox__exec 危险命令硬拒 — 与 orchestrator HITL 白名单互补。
 * 命中则抛错，不进入容器。
 */
final class SandboxExecGuard {

    private record DenyRule(Pattern pattern, String reason) {
    }

    private static final List<DenyRule> RULES = List.of(
            rule("(?i).*\\brm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+|--force\\s+)?(/\\s*(\\*|\\.\\.)?|/\\*|/~).*",
                    "destructive recursive delete of root/home"),
            rule("(?i).*\\bmkfs(\\.\\w+)?\\b.*", "filesystem format"),
            rule("(?i).*\\bdd\\b.*\\bof=/dev/.*", "raw disk write"),
            rule("(?i).*\\b(curl|wget)\\b.*\\|\\s*(ba)?sh\\b.*", "pipe remote script to shell"),
            rule("(?i).*:\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;\\s*:.*", "fork bomb"),
            rule("(?i).*\\bchmod\\s+(-R\\s+)?777\\s+/\\s*.*", "world-writable root"),
            rule("(?i).*\\bchown\\s+(-R\\s+)?.*\\s+/\\s*.*", "chown on root"),
            rule("(?i).*\\b(shutdown|reboot|halt|poweroff)\\b.*", "host power control"),
            rule("(?i).*\\bdocker\\b.*", "nested docker"),
            rule("(?i).*\\bkubectl\\b.*", "cluster control"));

    private SandboxExecGuard() {
    }

    /** @return 拒绝原因；允许则为 null */
    static String denyReason(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.strip();
        for (DenyRule rule : RULES) {
            if (rule.pattern().matcher(normalized).matches()) {
                return rule.reason();
            }
        }
        return null;
    }

    private static DenyRule rule(String regex, String reason) {
        return new DenyRule(Pattern.compile(regex), reason);
    }
}
