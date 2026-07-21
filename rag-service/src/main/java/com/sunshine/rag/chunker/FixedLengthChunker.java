package com.sunshine.rag.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 定长滑动窗口分块：优先在窗口内最后句末标点处切开 */
@Component
public class FixedLengthChunker implements Chunker {

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.FIXED;
    }

    @Override
    public List<ChunkDraft> chunk(String markdown, ChunkParams params) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        int maxSize = params.maxSize();
        int overlap = params.overlap();
        List<ChunkDraft> chunks = new ArrayList<>();
        int start = 0;
        int len = markdown.length();
        while (start < len) {
            int cutEnd = findCutEnd(markdown, start, maxSize);
            chunks.add(new ChunkDraft(chunks.size(), markdown.substring(start, cutEnd), Map.of()));
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

    /** 在 [start, start+maxSize) 内找最后句末标点，否则硬切到 maxSize */
    static int findCutEnd(String text, int start, int maxSize) {
        int windowEnd = Math.min(start + maxSize, text.length());
        if (windowEnd >= text.length()) {
            return text.length();
        }
        int best = -1;
        for (int i = start; i < windowEnd; i++) {
            if (isSentenceEnd(text.charAt(i))) {
                best = i + 1;
            }
        }
        return best > start ? best : windowEnd;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '\n';
    }
}
