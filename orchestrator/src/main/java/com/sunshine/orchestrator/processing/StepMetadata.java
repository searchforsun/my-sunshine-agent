package com.sunshine.orchestrator.processing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 时间线步骤结构化元数据（如 RAG 命中数与来源文档、QueryRewrite 可观测） */
public record StepMetadata(
        Integer hitCount,
        List<String> sources,
        Boolean rewriteApplied,
        Long rewriteLatencyMs,
        String rewriteFrom,
        String rewriteTo,
        String rewriteScenario,
        String rewriteScenarioLabel
) {

    private static final Pattern HIT_COUNT = Pattern.compile("(?:共|命中)\\s*(\\d+)\\s*条");
    private static final Pattern NO_HIT_HEADER = Pattern.compile("^未找到相关知识库");
    private static final Pattern SOURCE_DOC_LINE = Pattern.compile("来源文档[：:]\\s*([^\\n【]+)");
    private static final Pattern SOURCE_SUMMARY_LINE = Pattern.compile("来源[：:]\\s*([^\\n【]+)");
    private static final Pattern FRAGMENT_DOC = Pattern.compile("【([^|】]+)\\s*\\|");
    private static final int MAX_DOC_NAME_LEN = 80;

    /** 从 RAG 工具原始或已摘要文本提取命中数与去重文档标题 */
    public static StepMetadata fromRagToolOutput(String text) {
        return fromRagToolOutput(text, text);
    }

    /** 原始正文提取文档名，摘要行补充命中数 */
    public static StepMetadata fromRagToolOutput(String rawText, String summarizedText) {
        if (isEmptyRagOutput(rawText) && isEmptyRagOutput(summarizedText)) {
            return emptyRag();
        }
        int hitCount = parseHitCount(rawText);
        if (hitCount == 0 && summarizedText != null && !summarizedText.isBlank()) {
            hitCount = parseHitCount(summarizedText);
        }
        List<String> sources = parseSources(rawText);
        if (sources.isEmpty() && summarizedText != null && !summarizedText.isBlank()) {
            sources = parseSources(summarizedText);
        }
        return new StepMetadata(hitCount, sources, null, null, null, null, null, null);
    }

    public static StepMetadata fromRewrite(com.sunshine.orchestrator.rewrite.QueryRewriteOutcome outcome) {
        if (outcome == null || !outcome.applied()) {
            return null;
        }
        String scenarioLabel = RewriteTimelineLabels.labelFor(outcome.scenario());
        return new StepMetadata(
                null,
                null,
                true,
                outcome.latencyMs(),
                outcome.originalQuery(),
                outcome.rewrittenQuery(),
                outcome.scenario(),
                scenarioLabel.isBlank() ? null : scenarioLabel);
    }

    public static StepMetadata mergeRewrite(StepMetadata base, com.sunshine.orchestrator.rewrite.QueryRewriteOutcome outcome) {
        StepMetadata rewriteMeta = fromRewrite(outcome);
        if (rewriteMeta == null) {
            return base;
        }
        if (base == null) {
            return rewriteMeta;
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                rewriteMeta.rewriteApplied(),
                rewriteMeta.rewriteLatencyMs(),
                rewriteMeta.rewriteFrom(),
                rewriteMeta.rewriteTo(),
                rewriteMeta.rewriteScenario(),
                rewriteMeta.rewriteScenarioLabel());
    }

    private static StepMetadata emptyRag() {
        return new StepMetadata(0, List.of(), null, null, null, null, null, null);
    }

    public String sourcesLabel() {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return String.join("、", sources);
    }

    public boolean isEmpty() {
        return (hitCount == null || hitCount == 0)
                && (sources == null || sources.isEmpty())
                && (rewriteApplied == null || !rewriteApplied);
    }

    private static boolean isEmptyRagOutput(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String trimmed = text.strip();
        return NO_HIT_HEADER.matcher(trimmed).find();
    }

    private static int parseHitCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher matcher = HIT_COUNT.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    private static List<String> parseSources(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher fragment = FRAGMENT_DOC.matcher(text);
        while (fragment.find()) {
            addDocName(names, fragment.group(1));
        }
        if (!names.isEmpty()) {
            return new ArrayList<>(names);
        }
        Matcher docLine = SOURCE_DOC_LINE.matcher(text);
        if (docLine.find()) {
            splitDocNames(names, docLine.group(1));
        }
        if (names.isEmpty()) {
            Matcher summaryLine = SOURCE_SUMMARY_LINE.matcher(text);
            if (summaryLine.find()) {
                splitDocNames(names, summaryLine.group(1));
            }
        }
        return new ArrayList<>(names);
    }

    private static void splitDocNames(LinkedHashSet<String> names, String raw) {
        for (String part : raw.split("、")) {
            addDocName(names, part);
        }
    }

    private static void addDocName(LinkedHashSet<String> names, String name) {
        if (name == null) {
            return;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DOC_NAME_LEN) {
            return;
        }
        if (trimmed.contains("#") || trimmed.contains("|") || trimmed.contains("---")) {
            return;
        }
        names.add(trimmed);
    }
}
