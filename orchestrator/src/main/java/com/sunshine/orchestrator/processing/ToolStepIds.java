package com.sunshine.orchestrator.processing;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.OptionalLong;

/**
 * ReAct 每次工具调用对应独立步骤 id：{@code tool-{name}@{epochMs}}、{@code rag@{epochMs}}。
 */
public final class ToolStepIds {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ToolStepIds() {
    }

    public static boolean isToolStep(String stepId) {
        if (stepId == null) {
            return false;
        }
        return stepId.startsWith("tool-") || isRagStep(stepId);
    }

    public static boolean isRagStep(String stepId) {
        return "rag".equals(stripInvokeSuffix(stepId));
    }

    /** 带调用时刻的唯一步骤 id */
    public static String forInvocation(String baseStepId, long epochMs) {
        if (baseStepId == null || baseStepId.isBlank()) {
            return baseStepId;
        }
        return baseStepId + "@" + epochMs;
    }

    /** 去掉 {@code @{epochMs}} 后缀，还原 base stepId */
    public static String stripInvokeSuffix(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return stepId;
        }
        int at = stepId.lastIndexOf('@');
        if (at <= 0) {
            return stepId;
        }
        String suffix = stepId.substring(at + 1);
        if (!suffix.matches("\\d+")) {
            return stepId;
        }
        return stepId.substring(0, at);
    }

    public static OptionalLong invokeEpochMs(String stepId) {
        if (stepId == null) {
            return OptionalLong.empty();
        }
        int at = stepId.lastIndexOf('@');
        if (at <= 0) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(stepId.substring(at + 1)));
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    public static String invokeTimeLabel(String stepId) {
        return invokeEpochMs(stepId)
                .stream()
                .mapToObj(ms -> DISPLAY_TIME.withZone(DISPLAY_ZONE).format(Instant.ofEpochMilli(ms)))
                .findFirst()
                .orElse(null);
    }

    /** 解析 catalog 工具 id（{@code rag} → {@code search_knowledge}） */
    public static String catalogToolName(String stepId) {
        String base = stripInvokeSuffix(stepId);
        if ("rag".equals(base)) {
            return "search_knowledge";
        }
        if (base != null && base.startsWith("tool-")) {
            return base.substring("tool-".length());
        }
        return base;
    }
}
