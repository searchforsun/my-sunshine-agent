package com.sunshine.orchestrator.processing;

import java.util.Map;
import java.util.Set;

/**
 * 工具白名单标量入参提取（五层 §5.5.8：禁止整段 payload）。
 * 白名单键有值时只保留白名单键；否则回退为前两个标量参数，总量封顶。
 * 渲染形态：{@code 金额=3000 · 单据类型=报销}（确定性，零 LLM）。
 */
public final class ToolArgsRenderer {

    /** 业务白名单键（金额/单据/员工号等，契约见 §5.5.8 字段表） */
    private static final Set<String> WHITELIST_KEYS = Set.of(
            "amount", "totalAmount", "money",
            "orderId", "orderNo", "approvalId", "applyId", "billId", "billNo",
            "employeeId", "employeeNo", "userId", "empNo",
            "type", "docType", "billType", "expenseType",
            "date", "startDate", "endDate",
            "reason", "title", "name");

    private static final int MAX_TOTAL_LEN = 100;
    private static final int MAX_VALUE_LEN = 40;
    private static final int FALLBACK_ENTRIES = 2;

    private ToolArgsRenderer() {
    }

    public static String render(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int matched = 0;
        for (String key : WHITELIST_KEYS) {
            String value = scalarValue(input.get(key));
            if (value == null) {
                continue;
            }
            appendEntry(sb, key, value);
            matched++;
        }
        if (matched == 0) {
            int count = 0;
            for (Map.Entry<String, Object> e : input.entrySet()) {
                String value = scalarValue(e.getValue());
                if (value == null) {
                    continue;
                }
                appendEntry(sb, e.getKey(), value);
                if (++count >= FALLBACK_ENTRIES) {
                    break;
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 仅保留标量（字符串/数字/布尔）；数组/对象/多行文本不入白名单 */
    private static String scalarValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            String t = s.strip();
            if (t.isEmpty() || t.contains("\n")) {
                return null;
            }
            return t.length() > MAX_VALUE_LEN ? t.substring(0, MAX_VALUE_LEN) + "…" : t;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return null;
    }

    private static void appendEntry(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(key).append('=').append(value);
        if (sb.length() > MAX_TOTAL_LEN) {
            sb.setLength(MAX_TOTAL_LEN);
            sb.append('…');
        }
    }
}
