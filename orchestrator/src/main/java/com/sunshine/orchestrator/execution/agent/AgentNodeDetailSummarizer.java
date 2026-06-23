package com.sunshine.orchestrator.execution.agent;

import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/** Workflow agent 节点时间线：主行一行预览（前端截断）+ 展开区完整 after/detail */
public final class AgentNodeDetailSummarizer {

    private static final int MIN_PROSE_LEN = 8;

    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^[-*_\\s]{3,}$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\|.+\\|$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\|?\\s*:?-{2,}");
    private static final Pattern CJK = Pattern.compile("\\p{Script=Han}");
    private static final Pattern MARKDOWN_DECOR = Pattern.compile("[#>*`]|\\*\\*|__");

    private AgentNodeDetailSummarizer() {
    }

    public static String summarize(String answer, int toolCallCount) {
        return summarize(answer, null, toolCallCount);
    }

    /** 主行/展开首行 after：完整首句（不截断，主行省略由前端处理） */
    public static String summarize(String answer, String reasoning, int toolCallCount) {
        String line = pickPreviewLine(answer, false);
        if (line.isBlank() && reasoning != null && !reasoning.isBlank()) {
            line = pickPreviewLine(reasoning, true);
        }
        if (!line.isBlank()) {
            return toOneLine(line);
        }
        if (toolCallCount > 0) {
            return "已完成 " + toolCallCount + " 次工具调用的综合分析";
        }
        return "智能体分析完成";
    }

    /** 展开区 detail：可选前缀已加载技能，后接完整 agent 输出 */
    public static String expandDetail(String skillLabel, String answer) {
        if (!StringUtils.hasText(answer)) {
            return StringUtils.hasText(skillLabel) ? "已加载技能：" + skillLabel.strip() : "";
        }
        String body = answer.strip();
        if (!StringUtils.hasText(skillLabel)) {
            return body;
        }
        return "已加载技能：" + skillLabel.strip() + "\n\n" + body;
    }

    private static String toOneLine(String line) {
        return line.replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String pickPreviewLine(String text, boolean fromEnd) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.strip().split("\n");

        for (String keyword : new String[] {"结论", "综合判断", "无法判断", "无法给出", "建议"}) {
            String hit = findLineContaining(lines, keyword);
            if (!hit.isBlank()) {
                return hit;
            }
        }

        if (fromEnd) {
            for (int i = lines.length - 1; i >= 0; i--) {
                String prose = toProseLine(lines[i]);
                if (!prose.isBlank()) {
                    return prose;
                }
            }
        } else {
            for (String raw : lines) {
                String prose = toProseLine(raw);
                if (!prose.isBlank()) {
                    return prose;
                }
            }
        }
        return "";
    }

    private static String findLineContaining(String[] lines, String keyword) {
        for (String raw : lines) {
            String prose = toProseLine(raw);
            if (!prose.isBlank() && prose.contains(keyword)) {
                return prose;
            }
        }
        return "";
    }

    private static String toProseLine(String raw) {
        if (raw == null) {
            return "";
        }
        String line = raw.strip();
        if (!isProseCandidate(line)) {
            return "";
        }
        return normalize(stripInlineMarkdown(line));
    }

    private static boolean isProseCandidate(String line) {
        if (line.isEmpty()) {
            return false;
        }
        if (line.startsWith("#") || TABLE_ROW.matcher(line).matches()) {
            return false;
        }
        if (HORIZONTAL_RULE.matcher(line).matches() || TABLE_SEP.matcher(line).find()) {
            return false;
        }
        String plain = stripInlineMarkdown(line);
        if (plain.length() < MIN_PROSE_LEN) {
            return false;
        }
        if (MARKDOWN_DECOR.matcher(line).find() && !CJK.matcher(plain).find()) {
            return false;
        }
        return CJK.matcher(plain).find();
    }

    private static String stripInlineMarkdown(String line) {
        String s = line.strip();
        while (s.startsWith(">")) {
            s = s.substring(1).strip();
        }
        s = s.replaceAll("\\*\\*|__", "");
        s = s.replaceAll("`+", "");
        return s.strip();
    }

    private static String normalize(String line) {
        return line.replaceAll("\\s+", " ").strip();
    }
}
