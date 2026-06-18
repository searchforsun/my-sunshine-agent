package com.sunshine.orchestrator.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按 catalog outputSummaryKind 生成时间线摘要 */
public final class ToolResultSummarizer {

    private static final Pattern FINANCE_COUNT = Pattern.compile("共\\s*(\\d+)\\s*条");
    private static final Pattern SUMMARY_ROW = Pattern.compile(
            "status=([^|\\s]+)\\s*\\|\\s*count=(\\d+)\\s*\\|\\s*totalAmount=([\\d.]+)");
    private static final Pattern DETAIL_TITLE = Pattern.compile("-\\s*标题=(.+)");

    private ToolResultSummarizer() {
    }

    /** 按工具 id 或 outputSummaryKind 生成时间线摘要 */
    public static String summarize(String toolNameOrKind, String text) {
        String kind = isKnownKind(toolNameOrKind) ? toolNameOrKind : summaryKindFor(toolNameOrKind);
        return summarizeByKind(kind, text);
    }

    private static boolean isKnownKind(String value) {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case "hit-count", "finance-list", "finance-summary", "finance-detail", "oa-tasks", "truncate" -> true;
            default -> false;
        };
    }

    private static String summaryKindFor(String toolName) {
        if (toolName == null) {
            return "truncate";
        }
        return switch (toolName) {
            case "search_knowledge" -> "hit-count";
            case "list_finance_messages" -> "finance-list";
            case "summarize_finance_by_status" -> "finance-summary";
            case "get_finance_message_detail" -> "finance-detail";
            case "list_oa_tasks" -> "oa-tasks";
            default -> "truncate";
        };
    }

    public static String summarizeByKind(String outputSummaryKind, String text) {
        if (text == null || text.isBlank()) {
            return emptyMessage(outputSummaryKind);
        }
        String kind = outputSummaryKind != null ? outputSummaryKind : "truncate";
        return switch (kind) {
            case "hit-count" -> RagHitSummarizer.summarize(text);
            case "finance-list" -> summarizeFinanceList(text);
            case "finance-summary" -> summarizeFinanceSummary(text);
            case "finance-detail" -> summarizeFinanceDetail(text);
            case "oa-tasks" -> summarizeOaTasks(text);
            default -> truncate(text);
        };
    }

    private static String emptyMessage(String kind) {
        return switch (kind != null ? kind : "truncate") {
            case "hit-count" -> "命中 0 条";
            case "finance-list" -> "0 条财务消息";
            case "finance-summary" -> "无汇总数据";
            case "oa-tasks" -> "0 条 OA 待办";
            default -> "无结果";
        };
    }

    private static String summarizeFinanceList(String text) {
        if (text.contains("未查询")) {
            return "0 条财务消息";
        }
        Matcher matcher = FINANCE_COUNT.matcher(text);
        if (matcher.find()) {
            return matcher.group(1) + " 条财务消息";
        }
        return truncate(text);
    }

    private static String summarizeFinanceSummary(String text) {
        if (text.contains("未查询")) {
            return "无汇总数据";
        }
        Matcher matcher = SUMMARY_ROW.matcher(text);
        if (matcher.find()) {
            return matcher.group(1) + " " + matcher.group(2) + " 条，合计 ¥" + matcher.group(3);
        }
        return truncate(text);
    }

    private static String summarizeFinanceDetail(String text) {
        if (text.contains("未找到") || text.contains("请提供")) {
            return truncate(text);
        }
        Matcher matcher = DETAIL_TITLE.matcher(text);
        if (matcher.find()) {
            String title = matcher.group(1).trim();
            return title.length() > 40 ? title.substring(0, 40) + "…" : title;
        }
        return truncate(text);
    }

    private static String summarizeOaTasks(String text) {
        if (text.contains("未查询") || text.contains("暂无")) {
            return "0 条 OA 待办";
        }
        Matcher matcher = FINANCE_COUNT.matcher(text);
        if (matcher.find()) {
            return matcher.group(1) + " 条 OA 待办";
        }
        return truncate(text);
    }

    private static String truncate(String text) {
        String normalized = text.strip().replace('\n', ' ');
        if (normalized.length() <= 80) {
            return normalized;
        }
        return normalized.substring(0, 80) + "…";
    }
}
