package com.sunshine.orchestrator.processing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 RAG 工具原始/摘要文本解析命中数与来源文档 */
final class RagStepMetadataParser {

    private static final Pattern HIT_COUNT = Pattern.compile("(?:共|命中)\\s*(\\d+)\\s*条");
    private static final Pattern NO_HIT_HEADER = Pattern.compile("^未找到相关知识库");
    private static final Pattern SOURCE_DOC_LINE = Pattern.compile("来源文档[：:]\\s*([^\\n【]+)");
    private static final Pattern SOURCE_SUMMARY_LINE = Pattern.compile("来源[：:]\\s*([^\\n【]+)");
    private static final Pattern FRAGMENT_DOC = Pattern.compile("【([^|】]+)\\s*\\|");
    private static final int MAX_DOC_NAME_LEN = 80;

    private RagStepMetadataParser() {
    }

    static StepMetadata fromRagToolOutput(String text) {
        return fromRagToolOutput(text, text);
    }

    static StepMetadata fromRagToolOutput(String rawText, String summarizedText) {
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
        return new StepMetadata(hitCount, sources, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static StepMetadata emptyRag() {
        return new StepMetadata(0, List.of(), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static boolean isEmptyRagOutput(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return NO_HIT_HEADER.matcher(text.strip()).find();
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
