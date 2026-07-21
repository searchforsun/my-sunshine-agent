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
        List<int[]> ranges = splitToRanges(markdown, params.maxSize(), 0);
        List<String> texts = applyOverlapCutback(markdown, ranges, params.overlap());
        List<ChunkDraft> chunks = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(new ChunkDraft(i, texts.get(i), Map.of()));
        }
        return chunks;
    }

    private List<int[]> splitToRanges(String text, int maxSize, int sepIndex) {
        if (sepIndex >= SEPARATORS.length) {
            return text.length() <= maxSize ? List.of(range(0, text.length())) : characterSplitRanges(text, maxSize);
        }
        String separator = SEPARATORS[sepIndex];
        if (separator.isEmpty()) {
            return text.length() <= maxSize ? List.of(range(0, text.length())) : characterSplitRanges(text, maxSize);
        }
        List<int[]> parts = splitBySeparatorRanges(text, separator);
        if (parts.size() == 1) {
            if (text.length() <= maxSize) {
                return List.of(range(0, text.length()));
            }
            return splitToRanges(text, maxSize, sepIndex + 1);
        }
        List<int[]> result = new ArrayList<>();
        for (int[] part : parts) {
            if (part[1] <= part[0]) {
                continue;
            }
            int partLen = part[1] - part[0];
            if (partLen <= maxSize) {
                result.add(part);
            } else {
                String slice = text.substring(part[0], part[1]);
                for (int[] sub : splitToRanges(slice, maxSize, sepIndex + 1)) {
                    result.add(range(part[0] + sub[0], part[0] + sub[1]));
                }
            }
        }
        return result;
    }

    /**
     * 切点回退：相邻块共享 overlap 字符——下一块起点 = 上一块切点(end) - overlap；
     * 若回退后未越过上一块起点则退化为从切点继续（与 FixedLengthChunker 一致，避免死循环）。
     */
    static List<String> applyOverlapCutback(String text, List<int[]> ranges, int overlap) {
        if (ranges.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>(ranges.size());
        int prevStart = ranges.get(0)[0];
        int prevEnd = ranges.get(0)[1];
        chunks.add(text.substring(prevStart, prevEnd));
        for (int i = 1; i < ranges.size(); i++) {
            int end = ranges.get(i)[1];
            int start = ranges.get(i)[0];
            if (overlap > 0) {
                int nextStart = prevEnd - overlap;
                if (nextStart <= prevStart) {
                    nextStart = prevEnd;
                }
                start = Math.max(0, nextStart);
            }
            chunks.add(text.substring(start, end));
            prevStart = start;
            prevEnd = end;
        }
        return chunks;
    }

    static List<int[]> characterSplitRanges(String text, int maxSize) {
        List<int[]> ranges = new ArrayList<>();
        int start = 0;
        int len = text.length();
        while (start < len) {
            int cutEnd = Math.min(start + maxSize, len);
            ranges.add(range(start, cutEnd));
            if (cutEnd >= len) {
                break;
            }
            start = cutEnd;
        }
        return ranges;
    }

    static List<int[]> splitBySeparatorRanges(String text, String separator) {
        if ("\n\n".equals(separator)) {
            return splitByLiteralRanges(text, "\n\n");
        }
        if ("\n".equals(separator)) {
            return splitByLiteralRanges(text, "\n");
        }
        if ("。".equals(separator)) {
            List<int[]> parts = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '。') {
                    parts.add(range(start, i + 1));
                    start = i + 1;
                }
            }
            if (start < text.length()) {
                parts.add(range(start, text.length()));
            }
            return parts.isEmpty() ? List.of(range(0, text.length())) : parts;
        }
        return List.of(range(0, text.length()));
    }

    private static List<int[]> splitByLiteralRanges(String text, String separator) {
        List<int[]> parts = new ArrayList<>();
        int start = 0;
        int index = text.indexOf(separator);
        while (index >= 0) {
            parts.add(range(start, index));
            start = index + separator.length();
            index = text.indexOf(separator, start);
        }
        parts.add(range(start, text.length()));
        return parts;
    }

    private static int[] range(int start, int end) {
        return new int[]{start, end};
    }
}
