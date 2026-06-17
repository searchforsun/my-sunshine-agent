package com.sunshine.orchestrator.client;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 合并仅含空白字符的 content token 到相邻 content，避免裸 \\n 在 SSE 中被编码为单行空 data: 而丢失。
 * 策略：空白 token 缓冲；非空白 token 立即输出（前缀附带已缓冲空白）。
 * reasoning 不触发 content 刷新，避免 reasoning/content 交错时拆散数字与字段。
 */
public final class StreamTokenCoalescer {

    private StreamTokenCoalescer() {
    }

    public static Flux<StreamToken> coalesce(Flux<StreamToken> source) {
        AtomicReference<StringBuilder> contentBuffer = new AtomicReference<>(new StringBuilder());

        return source.concatMap(token -> {
                    if (token.isStep() || token.isStepDelta()) {
                        return flushContent(contentBuffer).concatWith(Mono.just(token));
                    }
                    if (token.isReasoning()) {
                        return Mono.just(token);
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
                    String combined = buf.toString() + text;
                    buf.setLength(0);
                    return Flux.just(StreamToken.content(combined));
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
