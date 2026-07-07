package com.sunshine.tool.summary;

import com.sunshine.tool.config.ToolTimelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Pattern;

/** 工具结果一步摘要文案 — 读 Nacos tool.timeline.result */
@Service
@RequiredArgsConstructor
public class ToolResultLabelService {

    private static final Pattern ZERO_HIT = Pattern.compile("命中\\s*0\\s*条");

    private final ToolTimelineProperties timelineProperties;

    public String emptyMessage(ToolOutputSummaryKind kind) {
        if (kind == null) {
            return defaultEmpty();
        }
        return switch (kind) {
            case HIT_COUNT -> hitCountZero();
            case FINANCE_LIST -> financeListZero();
            case FINANCE_SUMMARY -> financeSummaryZero();
            case OA_TASKS -> oaTasksZero();
            default -> defaultEmpty();
        };
    }

    public String hitCountZero() {
        return textOrDefault(timeline().getHitCount().getZero(), "命中 0 条");
    }

    public String hitCountWithCount(String hitCount) {
        return apply(timeline().getHitCount().getWithCount(), Map.of("hitCount", hitCount));
    }

    public String hitCountWithSources(String hitCount, String sources) {
        return apply(timeline().getHitCount().getWithSources(),
                Map.of("hitCount", hitCount, "sources", sources));
    }

    public String financeListZero() {
        return textOrDefault(timeline().getFinanceList().getZero(), "0 条财务消息");
    }

    public String financeListWithCount(String count) {
        return apply(timeline().getFinanceList().getWithCount(), Map.of("count", count));
    }

    public String financeSummaryZero() {
        return textOrDefault(timeline().getFinanceSummary().getZero(), "无汇总数据");
    }

    public String financeSummaryWithData(String status, String count, String amount) {
        return apply(timeline().getFinanceSummary().getWithData(),
                Map.of("status", status, "count", count, "amount", amount));
    }

    public String oaTasksZero() {
        ToolTimelineProperties.CountSummary cfg = timeline().getOaTasks();
        return textOrDefault(cfg != null ? cfg.getZero() : null, "0 条 OA 待办");
    }

    public String oaTasksWithCount(String count) {
        return apply(timeline().getOaTasks().getWithCount(), Map.of("count", count));
    }

    public String defaultEmpty() {
        return textOrDefault(timeline().getDefaultEmpty(), "无结果");
    }

    public int truncateMaxChars() {
        int max = timeline().getTruncateMaxChars();
        return max > 0 ? max : 80;
    }

    public boolean isZeroHitSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return true;
        }
        String stripped = summary.strip();
        if (stripped.equals(hitCountZero())) {
            return true;
        }
        return ZERO_HIT.matcher(stripped).find();
    }

    public boolean isEmptyToolSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return true;
        }
        return summary.strip().equals(defaultEmpty());
    }

    private ToolTimelineProperties.ResultTimeline timeline() {
        ToolTimelineProperties.ResultTimeline cfg = timelineProperties.getResult();
        return cfg != null ? cfg : new ToolTimelineProperties.ResultTimeline();
    }

    private static String apply(String template, Map<String, String> vars) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        String result = template.strip();
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }
}
