package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ToolResultSummarizer;
import com.sunshine.orchestrator.routing.ExecutionMode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 处理时间线各步骤的用户向摘要（结合用户问题生成） */
public final class StepSummarizer {

    private static final Pattern HIT_COUNT = Pattern.compile("(\\d+)");
    private static final Pattern RAG_HIT = Pattern.compile("命中\\s*(\\d+)\\s*条");
    private static final Pattern RAG_SOURCE = Pattern.compile("来源[：:](.+)");
    /** 问句摘要显示宽度预算（约 18 个汉字；拉丁字母按 1 计、CJK 按 2 计） */
    private static final int QUERY_DISPLAY_BUDGET = 36;
    private static final int RAG_SOURCE_CLIP = 80;

    private StepSummarizer() {
    }

    public static String clipQuery(String query) {
        if (query == null || query.isBlank()) {
            return "您的问题";
        }
        String trimmed = query.strip().replaceAll("\\s+", " ");
        String clipped = clipByDisplayBudget(trimmed, QUERY_DISPLAY_BUDGET);
        if (clipped.equals(trimmed)) {
            return "「" + trimmed + "」";
        }
        return "「" + clipped + "…」";
    }

    /** 按显示宽度截断：ASCII 1、CJK/全角 2，避免英文 @skill-id 被 18 字符硬切过短 */
    static String clipByDisplayBudget(String text, int budget) {
        if (text == null || text.isBlank() || budget <= 0) {
            return text != null ? text : "";
        }
        int used = 0;
        int end = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int width = displayWidth(cp);
            if (used + width > budget) {
                break;
            }
            used += width;
            end = i + Character.charCount(cp);
            i = end;
        }
        if (end >= text.length()) {
            return text;
        }
        return text.substring(0, end).stripTrailing();
    }

    private static int displayWidth(int codePoint) {
        if (codePoint <= 0x007F) {
            return 1;
        }
        if (codePoint >= 0xFF00 && codePoint <= 0xFFEF) {
            return 2;
        }
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) {
            return 2;
        }
        return 1;
    }

    public static String before(String stepId, String userQuery) {
        return before(stepId, userQuery, null, ExecutionMode.REACT);
    }

    public static String before(String stepId, String userQuery, String lastToolDisplayName) {
        return before(stepId, userQuery, lastToolDisplayName, ExecutionMode.REACT);
    }

    public static String before(String stepId, String userQuery, String lastToolDisplayName, ExecutionMode mode) {
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            if (mode == ExecutionMode.SIMPLE_LLM) {
                return ThinkStepIds.iterationOf(stepId) <= 1
                        ? "构思如何回答" + q
                        : "准备整理作答要点";
            }
            if (ThinkStepIds.iterationOf(stepId) <= 1) {
                return "规划如何回答" + q;
            }
            String tool = toolLabel(lastToolDisplayName);
            return tool != null ? "准备结合" + tool + "结果继续分析" : "准备结合工具结果分析" + q;
        }
        if (ToolStepIds.isRagStep(stepId)) {
            return TimelineLabels.before("rag", q);
        }
        return switch (stepId) {
            case "intent" -> TimelineLabels.before("intent", q);
            case "skill" -> SkillLoadLabels.before();
            case "rag" -> TimelineLabels.before("rag", q);
            case "agent" -> "理解" + q + "，规划作答思路";
            case "think" -> "分析" + q + "的作答逻辑";
            case "plan" -> TimelineLabels.before("plan", q);
            case "generate" -> TimelineLabels.before("generate", q);
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield "准备处理" + q + "的「" + com.sunshine.orchestrator.execution.WorkflowNodeLabels
                            .displayNameByStepId(stepId) + "」环节";
                }
                yield StepLabels.beforeFor(stepId);
            }
        };
    }

    public static String active(String stepId, String userQuery) {
        return active(stepId, userQuery, null, ExecutionMode.REACT);
    }

    public static String active(String stepId, String userQuery, String lastToolDisplayName) {
        return active(stepId, userQuery, lastToolDisplayName, ExecutionMode.REACT);
    }

    public static String active(String stepId, String userQuery, String lastToolDisplayName, ExecutionMode mode) {
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            if (mode == ExecutionMode.SIMPLE_LLM) {
                return ThinkStepIds.iterationOf(stepId) <= 1
                        ? "正在构思针对" + q + "的作答思路"
                        : "正在整理作答要点";
            }
            if (ThinkStepIds.iterationOf(stepId) <= 1) {
                return "正在规划" + q + "的工具调用方案";
            }
            String tool = toolLabel(lastToolDisplayName);
            return tool != null ? "正在综合分析" + tool + "返回结果" : "正在结合工具返回结果分析" + q;
        }
        if (ToolStepIds.isRagStep(stepId)) {
            return TimelineLabels.active("rag", q);
        }
        return switch (stepId) {
            case "intent" -> TimelineLabels.active("intent", q);
            case "skill" -> SkillLoadLabels.active();
            case "rag" -> TimelineLabels.active("rag", q);
            case "agent" -> "结合上下文分析" + q;
            case "think" -> "正在推演针对" + q + "的回答思路";
            case "plan" -> TimelineLabels.active("plan", q);
            case "generate" -> TimelineLabels.active("generate", q);
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    yield "正在" + com.sunshine.orchestrator.execution.WorkflowNodeLabels
                            .displayNameByStepId(stepId);
                }
                yield StepLabels.activeFor(stepId);
            }
        };
    }

    public static String after(String stepId, String userQuery, String detail) {
        return after(stepId, userQuery, detail, null, ExecutionMode.REACT);
    }

    public static String after(String stepId, String userQuery, String detail, String lastToolDisplayName) {
        return after(stepId, userQuery, detail, lastToolDisplayName, ExecutionMode.REACT);
    }

    public static String after(String stepId, String userQuery, String detail, String lastToolDisplayName,
            ExecutionMode mode) {
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            if (mode == ExecutionMode.SIMPLE_LLM) {
                return ThinkStepIds.iterationOf(stepId) <= 1
                        ? "已完成针对" + q + "的作答构思"
                        : "作答要点整理完成";
            }
            if (ThinkStepIds.iterationOf(stepId) <= 1) {
                return "已完成" + q + "的工具调用规划";
            }
            String tool = toolLabel(lastToolDisplayName);
            return tool != null ? "已完成" + tool + "的工具结果综合分析" : "工具结果综合分析已完成";
        }
        if (ToolStepIds.isRagStep(stepId)) {
            return afterRag(q, detail);
        }
        return switch (stepId) {
            case "intent" -> afterIntent(q, detail);
            case "skill" -> detail != null && !detail.isBlank() ? detail : "Skill 已加载";
            case "rag" -> afterRag(q, detail);
            case "agent" -> afterAgent(userQuery, detail);
            case "plan" -> detail != null ? detail : "执行计划已生成";
            case "think" -> "已完成对" + q + "的思考";
            case "generate" -> "已完成对" + q + "的回复";
            default -> {
                if (stepId != null && stepId.startsWith("node-")) {
                    if (detail != null && !detail.isBlank()) {
                        yield detail;
                    }
                    yield com.sunshine.orchestrator.execution.WorkflowNodeLabels
                            .displayNameByStepId(stepId) + "完成";
                }
                yield StepLabels.afterTemplate(stepId, detail);
            }
        };
    }

    public static String agentAfter(String userQuery, String ragDetailHint) {
        return afterAgent(userQuery, ragDetailHint);
    }

    public static String agentProgress(String userQuery) {
        String q = clipQuery(userQuery);
        return "深入分析" + q + "的背景与上下文";
    }

    private static String afterIntent(String q, String detail) {
        return IntentLabels.intentAfterSummary(q, detail);
    }

    private static String afterRag(String q, String detail) {
        return afterRag(q, detail, null);
    }

    /** 优先用 metadata 构造 after，避免摘要行误判为 0 条 */
    public static String afterRag(String q, String detail, StepMetadata metadata) {
        if (metadata != null && metadata.hitCount() != null && metadata.hitCount() > 0) {
            String sources = metadata.sourcesLabel();
            if (!sources.isBlank()) {
                return "找到 " + metadata.hitCount() + " 条参考片段，来源：" + sources;
            }
            return "找到 " + metadata.hitCount() + " 条与" + q + "相关的参考文档";
        }
        String input = detail != null ? detail : "";
        String summary = isAlreadyRagSummary(input)
                ? input.strip()
                : ToolResultSummarizer.summarize("search_knowledge", input);
        if (summary.contains("命中 0") || summary.contains("无结果") || summary.isBlank()) {
            return "未找到与" + q + "直接相关的制度或文档";
        }
        Matcher countMatcher = RAG_HIT.matcher(summary);
        if (!countMatcher.find()) {
            return "已完成针对" + q + "的知识库检索";
        }
        String hitCount = countMatcher.group(1);
        Matcher sourceMatcher = RAG_SOURCE.matcher(summary);
        if (sourceMatcher.find()) {
            String docNames = clipRagSource(sourceMatcher.group(1));
            return "找到 " + hitCount + " 条参考片段，来源：" + docNames;
        }
        return "找到 " + hitCount + " 条与" + q + "相关的参考文档";
    }

    /** 时间线只展示文档名，禁止把 RAG 片段正文拼进 after */
    private static String clipRagSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String line = raw.strip();
        int fragment = line.indexOf('【');
        if (fragment >= 0) {
            line = line.substring(0, fragment).trim();
        }
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline).trim();
        }
        if (line.length() > RAG_SOURCE_CLIP) {
            return line.substring(0, RAG_SOURCE_CLIP) + "…";
        }
        return line;
    }

    /** Hook 已摘要过的「命中 N 条」不再走 RagHitSummarizer（避免误识别为 0 条） */
    private static boolean isAlreadyRagSummary(String detail) {
        if (detail == null || detail.isBlank() || detail.length() > 200 || detail.contains("【")) {
            return false;
        }
        return RAG_HIT.matcher(detail.strip()).find();
    }

    private static String afterAgent(String userQuery, String ragDetailHint) {
        String q = clipQuery(userQuery);
        String detail = ragDetailHint;
        if (detail == null && userQuery == null) {
            return "完成问题分析，开始生成回复";
        }
        if (detail == null) {
            return "已梳理" + q + "的作答要点";
        }
        if (detail.contains("0 条")) {
            return "知识库暂无" + q + "的匹配内容，将结合通用知识作答";
        }
        Matcher matcher = HIT_COUNT.matcher(detail);
        if (matcher.find()) {
            return "已从 " + matcher.group(1) + " 条文档中提取与" + q + "相关的关键信息";
        }
        return "已完成对" + q + "的分析，开始生成回复";
    }

    /** 无用户问题时回退到通用文案 */
    public static String beforeFallback(String stepId) {
        return StepLabels.beforeFor(stepId);
    }

    private static String toolLabel(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        return "「" + displayName.strip() + "」";
    }

    public static String activeFallback(String stepId) {
        return StepLabels.activeFor(stepId);
    }

    public static String afterFallback(String stepId, String detail) {
        return StepLabels.afterTemplate(stepId, detail);
    }
}
