package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.SandboxPolicy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 沙箱工具 HITL 策略：读免确认；write/edit 在 never 下确认、smart 下免确认；exec 按只读命令白名单。
 */
public final class SandboxHitlPolicy {

    /** policy 未绑定时的默认只读 exec 白名单 */
    public static final List<String> DEFAULT_EXEC_READONLY_ALLOW = List.of(
            "ls *",
            "pwd",
            "python -m pytest *");

    /**
     * Catalog / shouldConfirmForBridge 层：只看 toolId。
     * EXEC 恒为 true（具体白名单在工具内再判）；write/edit 恒为 true（smart 下由工具内按 mode 豁免）；
     * read/glob/grep 为 false。
     */
    public static boolean catalogDefault(String toolId) {
        if (SandboxIds.READ.equals(toolId)
                || SandboxIds.GLOB.equals(toolId)
                || SandboxIds.GREP.equals(toolId)) {
            return false;
        }
        return SandboxIds.WRITE.equals(toolId)
                || SandboxIds.EDIT.equals(toolId)
                || SandboxIds.EXEC.equals(toolId);
    }

    /** 含参数的确认判定（exec 读当前 session policy 白名单）；mode 缺省等同 {@link SandboxWriteHitlMode#NEVER} */
    public static boolean requiresConfirmation(String toolId, Map<String, ?> params) {
        return requiresConfirmation(toolId, params, SandboxWriteHitlMode.NEVER);
    }

    /**
     * 写确认门闸 + Chat 工作区跳过模式。
     * {@code always}：写相关全部免确认；{@code smart}：write/edit 免确认，exec 仍走只读白名单；
     * {@code never}：write/edit 确认，exec 仍走只读白名单。
     */
    public static boolean requiresConfirmation(
            String toolId, Map<String, ?> params, SandboxWriteHitlMode mode) {
        SandboxWriteHitlMode m = mode != null ? mode : SandboxWriteHitlMode.NEVER;
        if (m == SandboxWriteHitlMode.ALWAYS) {
            return false;
        }
        boolean base = baseRequiresConfirmation(toolId, params);
        if (m == SandboxWriteHitlMode.SMART) {
            if (SandboxIds.WRITE.equals(toolId) || SandboxIds.EDIT.equals(toolId)) {
                return false;
            }
            // exec：危险仍确认，只读已在 base=false
            return base;
        }
        return base;
    }

    /** 不含跳过模式的基线判定 */
    static boolean baseRequiresConfirmation(String toolId, Map<String, ?> params) {
        if (SandboxIds.READ.equals(toolId)
                || SandboxIds.GLOB.equals(toolId)
                || SandboxIds.GREP.equals(toolId)) {
            return false;
        }
        if (SandboxIds.WRITE.equals(toolId) || SandboxIds.EDIT.equals(toolId)) {
            return true;
        }
        if (SandboxIds.EXEC.equals(toolId)) {
            Object command = params != null ? params.get("command") : null;
            String cmd = command != null ? String.valueOf(command) : null;
            return !isReadonlyExec(cmd, resolveExecReadonlyAllow());
        }
        return false;
    }

    /**
     * 命令是否命中只读白名单。
     * {@code allowList == null} 时用 {@link #DEFAULT_EXEC_READONLY_ALLOW}；
     * 空列表表示无白名单（一律需确认）。
     * glob：{@code *} → {@code .*}；前导空格+星号视为可选后缀（{@code ls *} 可匹配 {@code ls}）。
     */
    public static boolean isReadonlyExec(String command, List<String> allowList) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String cmd = command.strip();
        List<String> allows = allowList != null ? allowList : DEFAULT_EXEC_READONLY_ALLOW;
        for (String raw : allows) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (matchesGlob(cmd, raw.strip())) {
                return true;
            }
        }
        return false;
    }

    static List<String> resolveExecReadonlyAllow() {
        SandboxSessionHolder.Binding binding = SandboxSessionHolder.current();
        if (binding == null || binding.policy() == null) {
            return DEFAULT_EXEC_READONLY_ALLOW;
        }
        SandboxPolicy policy = binding.policy();
        return policy.execReadonlyAllow() != null
                ? policy.execReadonlyAllow()
                : DEFAULT_EXEC_READONLY_ALLOW;
    }

    static boolean matchesGlob(String command, String glob) {
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            if (i + 1 < glob.length() && glob.charAt(i) == ' ' && glob.charAt(i + 1) == '*') {
                re.append("(?: .*)?");
                i += 2;
            } else if (glob.charAt(i) == '*') {
                re.append(".*");
                i++;
            } else {
                re.append(Pattern.quote(String.valueOf(glob.charAt(i))));
                i++;
            }
        }
        re.append("$");
        return Pattern.compile(re.toString()).matcher(command).matches();
    }

    private SandboxHitlPolicy() {}
}
