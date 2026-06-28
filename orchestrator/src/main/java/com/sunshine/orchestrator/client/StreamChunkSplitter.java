package com.sunshine.orchestrator.client;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 将超大 content/reasoning token 切分为较小片段，保证前端始终有流式体验。
 * ReAct 分段契约（content_start / segmentId / content_end）原样透传。
 */
public final class StreamChunkSplitter {

    private StreamChunkSplitter() {
    }

    public static Flux<StreamToken> split(Flux<StreamToken> source, int maxChars) {
        if (maxChars <= 0) {
            return source;
        }
        return source.concatMap(token -> Flux.fromIterable(splitToken(token, maxChars)));
    }

    public static List<StreamToken> splitToken(StreamToken token, int maxChars) {
        if (token.isStep() || token.isContentStart() || token.isContentEnd()) {
            return List.of(token);
        }
        if (token.isStepDelta()) {
            return splitStepDelta(token, maxChars);
        }
        String text = token.text();
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= maxChars) {
            return List.of(token);
        }

        List<StreamToken> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + maxChars, text.length());
            if (end < text.length()) {
                int preferred = findPreferredBreak(text, i, end);
                if (preferred > i) {
                    end = preferred;
                }
            }
            String piece = text.substring(i, end);
            parts.add(toSplitPiece(token, piece));
            i = end;
        }
        return parts;
    }

    private static StreamToken toSplitPiece(StreamToken token, String piece) {
        StreamToken part;
        if (token.isReasoning()) {
            part = StreamToken.reasoning(piece);
        } else {
            String segmentId = token.segmentId();
            if (segmentId != null) {
                part = StreamToken.contentInSegment(segmentId, piece);
            } else {
                part = StreamToken.content(piece, token.afterStepId());
            }
        }
        if (token.scopeNodeStepId() != null) {
            return part.withScopeNodeStepId(token.scopeNodeStepId());
        }
        return part;
    }

    private static List<StreamToken> splitStepDelta(StreamToken token, int maxChars) {
        String text = token.text();
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= maxChars) {
            return List.of(token);
        }
        List<StreamToken> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + maxChars, text.length());
            if (end < text.length()) {
                int preferred = findPreferredBreak(text, i, end);
                if (preferred > i) {
                    end = preferred;
                }
            }
            parts.add(StreamToken.stepDelta(token.stepId(), token.channel(), text.substring(i, end)));
            i = end;
        }
        return parts;
    }

    /** 优先在换行/空格/标点处切分，避免截断英文单词（中文按字切即可） */
    private static int findPreferredBreak(String text, int start, int hardEnd) {
        for (int i = hardEnd - 1; i > start; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == ' ' || c == '\t' || isCjkPunctuation(c)) {
                return i + 1;
            }
        }
        return hardEnd;
    }

    private static boolean isCjkPunctuation(char c) {
        return c == '，' || c == '。' || c == '；' || c == '：' || c == '、'
                || c == '！' || c == '？' || c == '）' || c == '】';
    }
}
