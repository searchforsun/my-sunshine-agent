package com.sunshine.orchestrator.client;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 合并仅含空白字符的 content token 到相邻 content，避免裸 \\n 在 SSE 中被编码为单行空 data: 而丢失。
 * 策略与 mock-server.mjs tokenize 一致：换行/空格追加到前一个 content token。
 */
public final class StreamTokenCoalescer {

    private StreamTokenCoalescer() {
    }

    public static Flux<StreamToken> coalesce(Flux<StreamToken> source) {
        AtomicReference<StringBuilder> contentBuffer = new AtomicReference<>(new StringBuilder());

        return source.concatMap(token -> {
                    if (token.isStep()) {
                        return flushContent(contentBuffer).concatWith(Mono.just(token));
                    }
                    if (token.isReasoning()) {
                        return flushContent(contentBuffer).concatWith(Mono.just(token));
                    }
                    String text = token.text();
                    if (text.isEmpty()) {
                        return Flux.empty();
                    }
                    StringBuilder buf = contentBuffer.get();
                    if (isWhitespaceOnly(text)) {
                        buf.append(text);
                        return Flux.empty();
                    }
                    if (buf.length() > 0) {
                        String toEmit = buf.toString();
                        buf.setLength(0);
                        buf.append(text);
                        return Flux.just(StreamToken.content(toEmit));
                    }
                    buf.append(text);
                    return Flux.empty();
                })
                .concatWith(Flux.defer(() -> flushContent(contentBuffer)));
    }

    private static Flux<StreamToken> flushContent(AtomicReference<StringBuilder> contentBuffer) {
        StringBuilder buf = contentBuffer.get();
        if (buf.length() == 0) {
            return Flux.empty();
        }
        String text = buf.toString();
        buf.setLength(0);
        return Flux.just(StreamToken.content(text));
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
