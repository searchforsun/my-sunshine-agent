package com.sunshine.orchestrator.processing;

import org.springframework.util.StringUtils;

/** 工具步展开 detail：主行 after 为摘要，detail 保留原始多行输出供前端 Markdown 渲染 */
public final class ToolExpandDetailSupport {

    private ToolExpandDetailSupport() {
    }

    /**
     * @param summaryLine 时间线 after 摘要（catalog 模板解析）
     * @param rawText     工具原始返回
     */
    public static String resolveExpandDetail(String summaryLine, String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        String raw = rawText.strip();
        if (!StringUtils.hasText(summaryLine)) {
            return raw.contains("\n") ? raw : null;
        }
        String summary = summaryLine.strip();
        if (raw.equals(summary)) {
            return null;
        }
        if (raw.contains("\n") || raw.length() > summary.length()) {
            return raw;
        }
        return null;
    }
}
