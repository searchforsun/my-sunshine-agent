package com.sunshine.sandbox.tool;

import java.util.List;
import java.util.regex.Pattern;

/**
 * sandbox__exec 危险命令硬拒 — 与 orchestrator HITL 白名单互补。
 * 命中则抛错，不进入容器。
 *
 * <p>安全策略按场景分级：
 * <ul>
 *   <li><b>Chat 模式</b>：全量规则（黑名单），用户日常办公场景，严格限制</li>
 *   <li><b>Task 模式</b>：仅保留自杀式删除（根/家目录、git 裸库）+ fork bomb + pipe-sh
 *       （远程脚本管道执行），其余由 Docker --cap-drop ALL / --read-only / UID 10001 兜底；
 *       工作树 /workspace 与 /workspace/wt-* 内的 rm 正常放行</li>
 * </ul>
 */
final class SandboxExecGuard {

    private record DenyRule(Pattern pattern, String reason) {
    }

    /**
     * 自杀式 rm：仅当删除目标为根 / 家目录 ~ 或 git 裸库 /opt/git 时拒绝。
     * 兼容长短选项任意组合（rm -fr / rm -rf / rm -r -f / rm --recursive --force）与多目标；
     * /workspace（含 /workspace/wt-*）、/tmp 等项目内绝对路径正常放行。
     */
    private static final String RM_SUICIDE_REGEX =
            "(?i).*\\brm\\s+(?:(?:-{1,2}[a-zA-Z-]+\\s+)*"
                    + "(?:/|/\\*|/\\.\\.?|~|~/\\*|/opt/git(?:/\\*|/\\s|$))(?:\\s+|$))+.*";

    /** Chat 模式全量黑名单 */
    private static final List<DenyRule> CHAT_RULES = List.of(
            rule(RM_SUICIDE_REGEX, "destructive delete of root/home/git-store"),
            rule("(?i).*\\bmkfs(\\.\\w+)?\\b.*", "filesystem format"),
            rule("(?i).*\\bdd\\b.*\\bof=/dev/.*", "raw disk write"),
            rule("(?i).*\\b(curl|wget)\\b.*\\|\\s*(ba)?sh\\b.*", "pipe remote script to shell"),
            rule("(?i).*:\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;\\s*:.*", "fork bomb"),
            rule("(?i).*\\bchmod\\s+(-R\\s+)?777\\s+/\\s*.*", "world-writable root"),
            rule("(?i).*\\bchown\\s+(-R\\s+)?.*\\s+/\\s*.*", "chown on root"),
            rule("(?i).*\\b(shutdown|reboot|halt|poweroff)\\b.*", "host power control"),
            rule("(?i).*\\bdocker\\b.*", "nested docker"),
            rule("(?i).*\\bkubectl\\b.*", "cluster control"));

    /** Task 模式精简黑名单 — 仅保留跨容器不安全的攻击向量（工作区内 rm 放行） */
    private static final List<DenyRule> TASK_RULES = List.of(
            rule(RM_SUICIDE_REGEX, "destructive delete of root/home/git-store"),
            rule("(?i).*\\b(curl|wget)\\b.*\\|\\s*(ba)?sh\\b.*", "pipe remote script to shell"),
            rule("(?i).*:\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;\\s*:.*", "fork bomb"));

    private SandboxExecGuard() {
    }

    /** @return 拒绝原因；允许则为 null */
    static String denyReason(String command) {
        return denyReason(command, "chat");
    }

    /**
     * @param command 待执行的 shell 命令
     * @param kind 场景：{@code "chat"} 全量规则 / {@code "task"} 精简规则
     * @return 拒绝原因；允许则为 null
     */
    static String denyReason(String command, String kind) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.strip();
        List<DenyRule> rules = "task".equals(kind) ? TASK_RULES : CHAT_RULES;
        for (DenyRule rule : rules) {
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
