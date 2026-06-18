package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 处理时间线各步骤的用户向摘要（结合用户问题生成） */
public final class StepSummarizer {

    private static final Pattern HIT_COUNT = Pattern.compile("(\\d+)");
    private static final int QUERY_CLIP = 18;

    private StepSummarizer() {
    }

    public static String clipQuery(String query) {
        if (query == null || query.isBlank()) {
            return "您的问题";
        }
        String trimmed = query.strip().replaceAll("\\s+", " ");
        if (trimmed.length() <= QUERY_CLIP) {
            return "「" + trimmed + "」";
        }
        return "「" + trimmed.substring(0, QUERY_CLIP) + "…」";
    }

    public static String before(String stepId, String userQuery) {
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepIds.iterationOf(stepId) <= 1
                    ? "规划如何回答" + q
                    : "准备结合工具结果分析" + q;
        }
        return switch (stepId) {
            case "intent" -> TimelineLabels.before("intent", q);
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
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            return ThinkStepIds.iterationOf(stepId) <= 1
                    ? "正在规划" + q + "的工具调用方案"
                    : "正在结合工具返回结果分析" + q;
        }
        return switch (stepId) {
            case "intent" -> TimelineLabels.active("intent", q);
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
        String q = clipQuery(userQuery);
        if (ThinkStepIds.isThinkStep(stepId)) {
            if (detail != null && !detail.isBlank()) {
                return detail;
            }
            return ThinkStepIds.iterationOf(stepId) <= 1
                    ? "已完成" + q + "的工具调用规划"
                    : "已完成" + q + "的工具结果综合分析";
        }
        return switch (stepId) {
            case "intent" -> afterIntent(q, detail);
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
        if (detail == null || detail.contains("0 条")) {
            return "未找到与" + q + "直接相关的制度或文档";
        }
        if (detail.contains("来源：")) {
            int idx = detail.indexOf("来源：");
            String sources = detail.substring(idx + 3).trim();
            Matcher countMatcher = HIT_COUNT.matcher(detail);
            if (countMatcher.find()) {
                return "找到 " + countMatcher.group(1) + " 条参考片段，来源文档：" + sources;
            }
            return "已匹配到相关文档：" + sources;
        }
        Matcher matcher = HIT_COUNT.matcher(detail);
        if (matcher.find()) {
            return "找到 " + matcher.group(1) + " 条与" + q + "相关的参考文档";
        }
        return "已完成针对" + q + "的知识库检索";
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

    public static String activeFallback(String stepId) {
        return StepLabels.activeFor(stepId);
    }

    public static String afterFallback(String stepId, String detail) {
        return StepLabels.afterTemplate(stepId, detail);
    }
}
