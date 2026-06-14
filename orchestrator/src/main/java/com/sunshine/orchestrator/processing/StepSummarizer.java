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
        return switch (stepId) {
            case "intent" -> "阅读" + q;
            case "rag" -> "在企业知识库中查找与" + q + "相关的资料";
            case "agent" -> "理解" + q + "，规划作答思路";
            case "generate" -> "为" + q + "组织回答内容";
            default -> StepLabels.beforeFor(stepId);
        };
    }

    public static String active(String stepId, String userQuery) {
        String q = clipQuery(userQuery);
        return switch (stepId) {
            case "intent" -> "判断" + q + "该直接回答还是查阅知识库";
            case "rag" -> "正在匹配与" + q + "最相关的文档片段";
            case "agent" -> "结合检索结果分析" + q;
            case "generate" -> "正在撰写并输出针对" + q + "的回复";
            default -> StepLabels.activeFor(stepId);
        };
    }

    public static String after(String stepId, String userQuery, String detail) {
        String q = clipQuery(userQuery);
        return switch (stepId) {
            case "intent" -> afterIntent(q, detail);
            case "rag" -> afterRag(q, detail);
            case "agent" -> afterAgent(userQuery, detail);
            case "generate" -> "已完成对" + q + "的回复";
            default -> StepLabels.afterTemplate(stepId, detail);
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
        if (detail == null) {
            return "已完成对" + q + "的意图判断";
        }
        if ("知识库查询".equals(detail) || "knowledge".equalsIgnoreCase(detail)) {
            return q + "属于企业知识类问题，将检索知识库后作答";
        }
        if ("简单对话".equals(detail) || "simple".equalsIgnoreCase(detail)) {
            return q + "属于日常对话，将直接生成回复";
        }
        return q + "判定为：" + detail;
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
            return "完成问题分析，开始组织回答";
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
        return "已完成对" + q + "的分析，开始组织回答";
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
