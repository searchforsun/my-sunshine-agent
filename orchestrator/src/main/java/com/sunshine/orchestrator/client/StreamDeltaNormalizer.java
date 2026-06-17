package com.sunshine.orchestrator.client;

import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 LLM 累积式 delta（每帧携带已生成全文）转为真正增量 token。
 * 兼容增量式 delta（每帧仅新字符）——两种协议均可正确处理。
 */
public final class StreamDeltaNormalizer {

    private StreamDeltaNormalizer() {
    }

    public static Flux<StreamToken> normalizeTokens(Flux<StreamToken> source) {
        AtomicReference<String> lastContent = new AtomicReference<>("");
        AtomicReference<String> lastReasoning = new AtomicReference<>("");
        ConcurrentHashMap<String, AtomicReference<String>> lastStepReasoning = new ConcurrentHashMap<>();

        return source.mapNotNull(token -> {
            if (token.isStep()) {
                return token;
            }
            if (token.isStepDelta()) {
                if (!"reasoning".equals(token.channel())) {
                    return token;
                }
                AtomicReference<String> last = lastStepReasoning.computeIfAbsent(
                        token.stepId(), ignored -> new AtomicReference<>(""));
                String incremental = diff(last, token.text());
                return incremental.isEmpty()
                        ? null
                        : StreamToken.stepDelta(token.stepId(), token.channel(), incremental);
            }
            if (token.isReasoning()) {
                String incremental = diff(lastReasoning, token.text());
                return incremental.isEmpty() ? null : StreamToken.reasoning(incremental);
            }
            String incremental = diff(lastContent, token.text());
            return incremental.isEmpty() ? null : StreamToken.content(incremental);
        });
    }

    public static Flux<String> normalizeText(Flux<String> source) {
        AtomicReference<String> last = new AtomicReference<>("");
        return source.mapNotNull(text -> {
            String incremental = diff(last, text);
            return incremental.isEmpty() ? null : incremental;
        });
    }

    /**
     * @param lastFull 维护的已发送全文（会被更新）
     * @param incoming 本帧 payload
     * @return 仅本帧新增部分
     */
    static String diff(AtomicReference<String> lastFull, String incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return "";
        }
        String prev = lastFull.get();
        if (prev.isEmpty()) {
            lastFull.set(incoming);
            return incoming;
        }
        if (incoming.startsWith(prev)) {
            if (incoming.length() <= prev.length()) {
                return "";
            }
            String delta = incoming.substring(prev.length());
            lastFull.set(incoming);
            return delta;
        }
        if (prev.startsWith(incoming)) {
            lastFull.set(incoming);
            return "";
        }
        int overlap = longestSuffixPrefixOverlap(prev, incoming);
        if (overlap > 0) {
            String delta = incoming.substring(overlap);
            lastFull.set(prev + delta);
            return delta;
        }
        lastFull.set(prev + incoming);
        return incoming;
    }

    /** prev 后缀与 incoming 前缀的最长重叠，用于增量帧非前缀续写时的拼接 */
    static int longestSuffixPrefixOverlap(String prev, String incoming) {
        int max = Math.min(prev.length(), incoming.length());
        for (int len = max; len > 0; len--) {
            if (prev.regionMatches(prev.length() - len, incoming, 0, len)) {
                return len;
            }
        }
        return 0;
    }
}
