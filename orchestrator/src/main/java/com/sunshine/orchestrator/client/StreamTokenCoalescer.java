package com.sunshine.orchestrator.client;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 合并仅含空白字符的 content token 到相邻 content，避免裸 \\n 在 SSE 中被编码为单行空 data: 而丢失。
 * ReAct 分段契约（content_start / segmentId / content_end）原样透传。
 */
public final class StreamTokenCoalescer {

    private StreamTokenCoalescer() {
    }

    public static Flux<StreamToken> coalesce(Flux<StreamToken> source) {
        AtomicReference<StringBuilder> contentBuffer = new AtomicReference<>(new StringBuilder());
        AtomicReference<String> bufferedAfterStepId = new AtomicReference<>(null);

        return source.concatMap(token -> {
                    if (token.isContentLifecycle()) {
                        return flushContent(contentBuffer, bufferedAfterStepId).concatWith(Mono.just(token));
                    }
                    if (token.isStep() || token.isStepDelta()) {
                        return flushContent(contentBuffer, bufferedAfterStepId).concatWith(Mono.just(token));
                    }
                    if (token.isReasoning()) {
                        return Mono.just(token);
                    }
                    String text = token.text();
                    if (text == null || text.isEmpty()) {
                        return Flux.empty();
                    }
                    StringBuilder buf = contentBuffer.get();
                    if (isWhitespaceOnly(text)) {
                        buf.append(text);
                        return Flux.empty();
                    }
                    String combined = buf.toString() + text;
                    buf.setLength(0);
                    String anchor = bufferedAfterStepId.getAndSet(null);
                    if (anchor == null) {
                        anchor = token.afterStepId();
                    }
                    return Flux.just(anchor != null ? StreamToken.content(combined, anchor) : StreamToken.content(combined));
                })
                .concatWith(Flux.defer(() -> flushContent(contentBuffer, bufferedAfterStepId)));
    }

    private static Flux<StreamToken> flushContent(
            AtomicReference<StringBuilder> contentBuffer,
            AtomicReference<String> bufferedAfterStepId) {
        StringBuilder buf = contentBuffer.get();
        if (buf.length() == 0) {
            return Flux.empty();
        }
        String text = buf.toString();
        buf.setLength(0);
        String anchor = bufferedAfterStepId.getAndSet(null);
        return Flux.just(anchor != null ? StreamToken.content(text, anchor) : StreamToken.content(text));
    }

    static boolean isWhitespaceOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
