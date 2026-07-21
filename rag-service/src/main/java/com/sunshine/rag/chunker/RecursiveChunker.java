package com.sunshine.rag.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 递归分隔符分块：\n\n → \n → 。 → 字符；overlap 为切点回退 */
@Component
public class RecursiveChunker implements Chunker {

    private static final String[] SEPARATORS = {"\n\n", "\n", "。", ""};

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.RECURSIVE;
    }

    @Override
    public List<ChunkDraft> chunk(String markdown, ChunkParams params) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<String> texts = splitToFit(markdown, params.maxSize(), params.overlap(), 0);
        List<ChunkDraft> chunks = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(new ChunkDraft(i, texts.get(i), Map.of()));
        }
        return chunks;
    }

    private List<String> splitToFit(String text, int maxSize, int overlap, int sepIndex) {
        if (sepIndex >= SEPARATORS.length) {
            return text.length() <= maxSize ? List.of(text) : characterSplit(text, maxSize, overlap);
        }
        String separator = SEPARATORS[sepIndex];
        if (separator.isEmpty()) {
            return text.length() <= maxSize ? List.of(text) : characterSplit(text, maxSize, overlap);
        }
        List<String> parts = splitBySeparator(text, separator);
        if (parts.size() == 1) {
            if (text.length() <= maxSize) {
                return List.of(text);
            }
            return splitToFit(text, maxSize, overlap, sepIndex + 1);
        }
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() <= maxSize) {
                result.add(part);
            } else {
                result.addAll(splitToFit(part, maxSize, overlap, sepIndex + 1));
            }
        }
        return result;
    }

    static List<String> splitBySeparator(String text, String separator) {
        if ("\n\n".equals(separator)) {
            return splitByLiteral(text, "\n\n");
        }
        if ("\n".equals(separator)) {
            return splitByLiteral(text, "\n");
        }
        if ("。".equals(separator)) {
            List<String> parts = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '。') {
                    parts.add(text.substring(start, i + 1));
                    start = i + 1;
                }
            }
            if (start < text.length()) {
                parts.add(text.substring(start));
            }
            return parts.isEmpty() ? List.of(text) : parts;
        }
        return List.of(text);
    }

    private static List<String> splitByLiteral(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index = text.indexOf(separator);
        while (index >= 0) {
            parts.add(text.substring(start, index));
            start = index + separator.length();
            index = text.indexOf(separator, start);
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** 字符级切分：下一块起点 = 切点 - overlap（>= 0） */
    static List<String> characterSplit(String text, int maxSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int len = text.length();
        while (start < len) {
            int cutEnd = Math.min(start + maxSize, len);
            chunks.add(text.substring(start, cutEnd));
            if (cutEnd >= len) {
                break;
            }
            int nextStart = cutEnd - overlap;
            if (nextStart <= start) {
                nextStart = cutEnd;
            }
            start = Math.max(0, nextStart);
        }
        return chunks;
    }
}
