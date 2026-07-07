package com.sunshine.tool.summary;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 按 catalog outputSummaryKind 解析工具原始输出为一行摘要 */
@Component
@RequiredArgsConstructor
public class ToolOutputSummarizer {

    private static final Pattern FINANCE_COUNT = Pattern.compile("共\\s*(\\d+)\\s*条");
    private static final Pattern SUMMARY_ROW = Pattern.compile(
            "status=([^|\\s]+)\\s*\\|\\s*count=(\\d+)\\s*\\|\\s*totalAmount=([\\d.]+)");
    private static final Pattern DETAIL_TITLE = Pattern.compile("-\\s*标题=(.+)");

    private final ToolResultLabelService labels;
    private final RagHitSummarizer ragHitSummarizer;

    public String summarizeByKind(String outputSummaryKind, String text) {
        ToolOutputSummaryKind kind = ToolOutputSummaryKind.of(outputSummaryKind)
                .orElse(ToolOutputSummaryKind.TRUNCATE);
        if (text == null || text.isBlank()) {
            return labels.emptyMessage(kind);
        }
        return switch (kind) {
            case HIT_COUNT -> ragHitSummarizer.summarize(text);
            case FINANCE_LIST -> summarizeFinanceList(text);
            case FINANCE_SUMMARY -> summarizeFinanceSummary(text);
            case FINANCE_DETAIL -> summarizeFinanceDetail(text);
            case OA_TASKS -> summarizeOaTasks(text);
            default -> truncate(text);
        };
    }

    private String summarizeFinanceList(String text) {
        if (text.contains("未查询")) {
            return labels.financeListZero();
        }
        Matcher matcher = FINANCE_COUNT.matcher(text);
        if (matcher.find()) {
            return labels.financeListWithCount(matcher.group(1));
        }
        return truncate(text);
    }

    private String summarizeFinanceSummary(String text) {
        if (text.contains("未查询")) {
            return labels.financeSummaryZero();
        }
        Matcher matcher = SUMMARY_ROW.matcher(text);
        if (matcher.find()) {
            return labels.financeSummaryWithData(matcher.group(1), matcher.group(2), matcher.group(3));
        }
        return truncate(text);
    }

    private String summarizeFinanceDetail(String text) {
        if (text.contains("未找到") || text.contains("请提供")) {
            return truncate(text);
        }
        Matcher matcher = DETAIL_TITLE.matcher(text);
        if (matcher.find()) {
            String title = matcher.group(1).trim();
            int max = labels.truncateMaxChars();
            return title.length() > max ? title.substring(0, max) + "…" : title;
        }
        return truncate(text);
    }

    private String summarizeOaTasks(String text) {
        if (text.contains("未查询") || text.contains("暂无")) {
            return labels.oaTasksZero();
        }
        Matcher matcher = FINANCE_COUNT.matcher(text);
        if (matcher.find()) {
            return labels.oaTasksWithCount(matcher.group(1));
        }
        return truncate(text);
    }

    private String truncate(String text) {
        String normalized = text.strip().replace('\n', ' ');
        int max = labels.truncateMaxChars();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "…";
    }
}
