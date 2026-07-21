package com.sunshine.orchestrator.hitl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.util.StringUtils;

/**
 * HITL 参数摘要：确认框只展示路径等元信息；正文（write→content、exec→command）
 * 进工具步骤 detail；edit 预览只走 {@code metadata.editDiff}（EditDiffBuilder）。
 */
public final class HitlParamSupport {

    /** 正文/命令类参数 — 不上 HITL 确认框，进展开区 */
    public static final Set<String> BODY_PARAM_KEYS = Set.of(
            "content", "new_string", "old_string", "command");

    private HitlParamSupport() {
    }

    static Map<String, String> parseParamsSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : summary.split(",\\s*(?=[\\w.-]+=)")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return map;
    }

    /** HITL 确认框摘要：排除正文类参数，单行 key=value，过长截断 */
    public static String summarizeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .filter(e -> e.getKey() != null && !isBodyParamKey(e.getKey()))
                .map(e -> e.getKey() + "=" + truncateParamValue(e.getValue()))
                .collect(Collectors.joining(", "));
    }

    /** 工具步骤展开用全文（write→content；exec→command；edit 不在此生成） */
    public static String expandBodyFromParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        String content = params.get("content");
        if (StringUtils.hasText(content)) {
            return content;
        }
        String command = params.get("command");
        return StringUtils.hasText(command) ? command : null;
    }

    /**
     * edit 展开：行级 unified（公共行前缀空格，删除 -，新增 +），同屏对照。
     */
    public static String formatEditUnifiedDiff(String oldStr, String newStr) {
        String[] a = splitLines(oldStr != null ? oldStr : "");
        String[] b = splitLines(newStr != null ? newStr : "");
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a[i].equals(b[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                appendDiffLine(sb, ' ', a[i]);
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                appendDiffLine(sb, '-', a[i++]);
            } else {
                appendDiffLine(sb, '+', b[j++]);
            }
        }
        while (i < n) {
            appendDiffLine(sb, '-', a[i++]);
        }
        while (j < m) {
            appendDiffLine(sb, '+', b[j++]);
        }
        return sb.toString();
    }

    private static String[] splitLines(String text) {
        if (text.isEmpty()) {
            return new String[]{""};
        }
        return text.split("\n", -1);
    }

    private static void appendDiffLine(StringBuilder sb, char prefix, String line) {
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(prefix).append(line);
    }

    public static boolean isBodyParamKey(String key) {
        return key != null && BODY_PARAM_KEYS.contains(key.strip());
    }

    private static String truncateParamValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().replace('\n', ' ');
        int maxLen = 120;
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "…";
    }
}
