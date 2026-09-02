package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.routing.ExecutionMode;

import java.util.Optional;

/** 处理时间线各步骤的用户向摘要（结合用户问题生成） */
public final class StepSummarizer {

    /** 问句摘要显示宽度预算（约 18 个汉字；拉丁字母按 1 计、CJK 按 2 计） */
    private static final int QUERY_DISPLAY_BUDGET = 36;

    private StepSummarizer() {
    }

    public static String clipQuery(String query) {
        if (query == null || query.isBlank()) {
            return "您的问题";
        }
        String trimmed = query.strip().replaceAll("\\s+", " ");
        String clipped = clipByDisplayBudget(trimmed, QUERY_DISPLAY_BUDGET);
        return "「" + clipped + "」";
    }

    /** 按显示宽度截断：ASCII 1、CJK/全角 2，避免英文 /skill-id 被 18 字符硬切过短 */
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
        return before(stepId, userQuery, null, ExecutionMode.FAST);
    }

    public static String before(String stepId, String userQuery, String lastToolDisplayName) {
        return before(stepId, userQuery, lastToolDisplayName, ExecutionMode.FAST);
    }

    public static String before(String stepId, String userQuery, String lastToolDisplayName, ExecutionMode mode) {
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepLabels.before(stepId, mode, q, lastToolDisplayName);
        }
        if (ToolStepIds.isRagStep(stepId)) {
            return TimelineLabels.before(TimelineStepId.RAG.id(), q);
        }
        Optional<TimelineStepId> standard = TimelineStepId.of(stepId);
        if (standard.isEmpty()) {
            if (TimelineStepId.isNodeStep(stepId)) {
                return ToolNodeLabels.nodeBefore(stepId, q, null);
            }
            return StepLabels.beforeFor(stepId);
        }
        return switch (standard.get()) {
            case INTENT -> TimelineLabels.before(TimelineStepId.INTENT.id(), q);
            case SKILL -> SkillLoadLabels.before();
            case RAG -> TimelineLabels.before(TimelineStepId.RAG.id(), q);
            case AGENT -> SummaryStepLabels.agentBefore(q);
            case PLAN -> TimelineLabels.before(TimelineStepId.PLAN.id(), q);
            case GENERATE -> TimelineLabels.before(TimelineStepId.GENERATE.id(), q);
            case TASKS -> TaskBoardStepLabels.before();
            default -> StepLabels.beforeFor(stepId);
        };
    }

    public static String active(String stepId, String userQuery) {
        return active(stepId, userQuery, null, ExecutionMode.FAST);
    }

    public static String active(String stepId, String userQuery, String lastToolDisplayName) {
        return active(stepId, userQuery, lastToolDisplayName, ExecutionMode.FAST);
    }

    public static String active(String stepId, String userQuery, String lastToolDisplayName, ExecutionMode mode) {
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepLabels.active(stepId, mode, q, lastToolDisplayName);
        }
        if (ToolStepIds.isRagStep(stepId)) {
            return TimelineLabels.active(TimelineStepId.RAG.id(), q);
        }
        Optional<TimelineStepId> standard = TimelineStepId.of(stepId);
        if (standard.isEmpty()) {
            if (TimelineStepId.isNodeStep(stepId)) {
                return ToolNodeLabels.nodeActive(stepId, null);
            }
            return StepLabels.activeFor(stepId);
        }
        return switch (standard.get()) {
            case INTENT -> TimelineLabels.active(TimelineStepId.INTENT.id(), q);
            case SKILL -> SkillLoadLabels.active();
            case RAG -> TimelineLabels.active(TimelineStepId.RAG.id(), q);
            case AGENT -> SummaryStepLabels.agentActive(q);
            case PLAN -> TimelineLabels.active(TimelineStepId.PLAN.id(), q);
            case GENERATE -> TimelineLabels.active(TimelineStepId.GENERATE.id(), q);
            case TASKS -> TaskBoardStepLabels.active("");
            default -> StepLabels.activeFor(stepId);
        };
    }

    public static String after(String stepId, String userQuery, String detail) {
        return after(stepId, userQuery, detail, null, ExecutionMode.FAST);
    }

    public static String after(String stepId, String userQuery, String detail, String lastToolDisplayName) {
        return after(stepId, userQuery, detail, lastToolDisplayName, ExecutionMode.FAST);
    }

    public static String after(String stepId, String userQuery, String detail, String lastToolDisplayName,
            ExecutionMode mode) {
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            return ThinkStepLabels.after(stepId, mode, q, lastToolDisplayName);
        }
        if (ToolStepIds.isRagStep(stepId)) {
            return afterRag(q, detail);
        }
        Optional<TimelineStepId> standard = TimelineStepId.of(stepId);
        if (standard.isEmpty()) {
            if (TimelineStepId.isNodeStep(stepId)) {
                return ToolNodeLabels.nodeAfter(stepId, detail, null);
            }
            return StepLabels.afterTemplate(stepId, detail);
        }
        return switch (standard.get()) {
            case INTENT -> IntentLabels.intentAfterSummary(q, detail);
            case SKILL -> TimelineLabels.after(TimelineStepId.SKILL.id(), q, detail);
            case RAG -> afterRag(q, detail);
            case AGENT -> SummaryStepLabels.agentAfter(userQuery, detail);
            case PLAN -> TimelineLabels.after(TimelineStepId.PLAN.id(), q, detail);
            case GENERATE -> TimelineLabels.after(TimelineStepId.GENERATE.id(), q, detail);
            case TASKS -> detail != null && !detail.isBlank() ? detail : TaskBoardStepLabels.after();
            default -> StepLabels.afterTemplate(stepId, detail);
        };
    }

    public static String agentAfter(String userQuery, String ragDetailHint) {
        return SummaryStepLabels.agentAfter(userQuery, ragDetailHint);
    }

    public static String agentProgress(String userQuery) {
        return SummaryStepLabels.agentProgress(clipQuery(userQuery));
    }

    /** 优先用 metadata 构造 after，避免摘要行误判为 0 条 */
    public static String afterRag(String q, String detail, StepMetadata metadata) {
        return SummaryStepLabels.ragAfter(q, detail, metadata);
    }

    private static String afterRag(String q, String detail) {
        return afterRag(q, detail, null);
    }

    /** 无用户问题时回退到通用文案 */
    public static String beforeFallback(String stepId) {
        return StepLabels.beforeFor(stepId);
    }

    public static String activeFallback(String stepId) {
        return StepLabels.activeFor(stepId);
    }

    public static String afterFallback(String stepId, String detail) {
        return StepLabels.afterTemplate(stepId, detail);
    }
}
