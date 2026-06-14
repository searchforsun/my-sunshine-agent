package com.sunshine.orchestrator.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 RagTool 返回文本中提取命中数与文档名称 */
final class RagHitSummarizer {

    private static final Pattern HIT_COUNT = Pattern.compile("共\\s*(\\d+)\\s*条");
    private static final Pattern SOURCE_DOCS = Pattern.compile("来源文档[：:]\\s*(.+)");

    private RagHitSummarizer() {
    }

    static String summarize(String text) {
        if (text == null || text.isBlank() || text.contains("未找到")) {
            return "命中 0 条";
        }
        Matcher countMatcher = HIT_COUNT.matcher(text);
        if (!countMatcher.find()) {
            return "命中 0 条";
        }
        String count = countMatcher.group(1);
        Matcher docMatcher = SOURCE_DOCS.matcher(text);
        if (docMatcher.find()) {
            String docLine = docMatcher.group(1).trim();
            int newline = docLine.indexOf('\n');
            String docNames = newline >= 0 ? docLine.substring(0, newline).trim() : docLine;
            if (!docNames.isEmpty()) {
                return "命中 " + count + " 条，来源：" + docNames;
            }
        }
        return "命中 " + count + " 条";
    }
}
