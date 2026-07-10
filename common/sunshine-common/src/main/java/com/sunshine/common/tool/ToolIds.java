package com.sunshine.common.tool;

import java.util.regex.Pattern;

/**
 * Catalog 工具 ID 规则（SSOT）：{@code sdk__{appId}__{name}} / {@code mcp__{serverId}__{name}}。
 * 与 DeepSeek 等 LLM function name 共用 {@link #LLM_SAFE}，禁止 {@code .} 与转换层。
 */
public final class ToolIds {

    /** LLM / Catalog 工具 ID 允许的字符集 */
    public static final Pattern LLM_SAFE = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private ToolIds() {
    }

    public static String sdk(String appId, String externalName) {
        return join("sdk", appId, externalName);
    }

    public static String mcp(String serverId, String externalName) {
        return join("mcp", serverId, externalName);
    }

    private static String join(String prefix, String sourceRef, String externalName) {
        return prefix + "__" + sourceRef + "__" + externalName;
    }

    public static boolean isValid(String id) {
        return invalidReason(id) == null;
    }

    public static boolean isValidSegment(String segment) {
        return segment != null && !segment.isBlank() && LLM_SAFE.matcher(segment).matches();
    }

    /** @return null 表示合法；否则为可读原因 */
    public static String invalidReason(String id) {
        if (id == null || id.isBlank()) {
            return "工具 ID 不能为空";
        }
        if (id.contains(".")) {
            return "工具 ID 不允许包含 '.'，请使用 __ 拼接（如 sdk__app__tool）";
        }
        if (!LLM_SAFE.matcher(id).matches()) {
            return "工具 ID 仅允许字母、数字、下划线与连字符";
        }
        return null;
    }
}
