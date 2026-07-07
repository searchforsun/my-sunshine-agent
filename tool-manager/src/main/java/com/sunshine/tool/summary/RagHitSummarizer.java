package com.sunshine.tool.summary;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 search_knowledge 原始输出提取命中数与来源 */
@Component
@RequiredArgsConstructor
public class RagHitSummarizer {

    private static final Pattern HIT_COUNT = Pattern.compile("共\\s*(\\d+)\\s*条");
    private static final Pattern NO_HIT_HEADER = Pattern.compile("^未找到相关知识库");
    private static final Pattern SOURCE_DOCS = Pattern.compile("来源文档[：:]\\s*([^\\n【]+)");

    private final ToolResultLabelService labels;

    public String summarize(String text) {
        if (text == null || text.isBlank()) {
            return labels.hitCountZero();
        }
        if (NO_HIT_HEADER.matcher(text.strip()).find()) {
            return labels.hitCountZero();
        }
        Matcher countMatcher = HIT_COUNT.matcher(text);
        if (!countMatcher.find()) {
            return labels.hitCountZero();
        }
        String count = countMatcher.group(1);
        Matcher docMatcher = SOURCE_DOCS.matcher(text);
        if (docMatcher.find()) {
            String docNames = docMatcher.group(1).trim();
            if (!docNames.isEmpty()) {
                return labels.hitCountWithSources(count, docNames);
            }
        }
        return labels.hitCountWithCount(count);
    }
}
