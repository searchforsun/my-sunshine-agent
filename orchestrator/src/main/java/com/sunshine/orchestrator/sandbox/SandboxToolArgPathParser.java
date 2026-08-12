package com.sunshine.orchestrator.sandbox;

/**
 * 从流式 tool_call arguments（半截 JSON）尽早解析 path。
 * write/edit 的 path 通常在 content 之前，便于参数生成空档就展示「正在写入 {path}」。
 */
public final class SandboxToolArgPathParser {

    private SandboxToolArgPathParser() {
    }

    /**
     * @return 已完整闭合的 path 字符串；半截/缺失时 null
     */
    public static String extractPath(String partialArgsJson) {
        if (partialArgsJson == null || partialArgsJson.isBlank()) {
            return null;
        }
        int key = indexOfPathKey(partialArgsJson);
        if (key < 0) {
            return null;
        }
        int colon = partialArgsJson.indexOf(':', key);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < partialArgsJson.length() && Character.isWhitespace(partialArgsJson.charAt(i))) {
            i++;
        }
        if (i >= partialArgsJson.length() || partialArgsJson.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder out = new StringBuilder();
        while (i < partialArgsJson.length()) {
            char c = partialArgsJson.charAt(i++);
            if (c == '\\') {
                if (i >= partialArgsJson.length()) {
                    return null; // 转义未完成
                }
                char n = partialArgsJson.charAt(i++);
                out.append(switch (n) {
                    case '"', '\\', '/' -> n;
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> n;
                });
                continue;
            }
            if (c == '"') {
                String path = out.toString().strip();
                return path.isEmpty() ? null : path;
            }
            out.append(c);
        }
        return null; // 引号未闭合
    }

    private static int indexOfPathKey(String json) {
        // 匹配 "path" 键（忽略前后空白由调用处处理；要求键两侧为 JSON 结构分隔）
        int from = 0;
        while (from < json.length()) {
            int idx = json.indexOf("\"path\"", from);
            if (idx < 0) {
                return -1;
            }
            if (idx == 0 || isJsonKeyBoundary(json.charAt(idx - 1))) {
                return idx;
            }
            from = idx + 6;
        }
        return -1;
    }

    private static boolean isJsonKeyBoundary(char c) {
        return c == '{' || c == ',' || Character.isWhitespace(c);
    }
}
