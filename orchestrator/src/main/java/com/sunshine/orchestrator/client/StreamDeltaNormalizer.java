package com.sunshine.orchestrator.client;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 LLM 累积式 delta（每帧携带已生成全文）转为真正增量 token。
 * 仅做前缀单调 diff，供 simple-llm / LLM Gateway 路径使用；ReAct 正文分段由 {@link com.sunshine.orchestrator.processing.ContentSegmentCoordinator} 负责。
 */
public final class StreamDeltaNormalizer {

    private StreamDeltaNormalizer() {
    }

    public static Flux<StreamToken> normalizeTokens(Flux<StreamToken> source) {
        AtomicReference<String> lastContent = new AtomicReference<>("");
        AtomicReference<String> lastReasoning = new AtomicReference<>("");
        Map<String, AtomicReference<String>> lastByStepReasoning = new ConcurrentHashMap<>();

        return source.mapNotNull(token -> {
            if (token.isContentLifecycle()) {
                return token;
            }
            if (token.isStep()) {
                return token;
            }
            if (token.isStepDelta()) {
                if (!"reasoning".equals(token.channel())) {
                    return token;
                }
                String stepId = token.stepId() != null ? token.stepId() : "";
                AtomicReference<String> lastStep = lastByStepReasoning
                        .computeIfAbsent(stepId, k -> new AtomicReference<>(""));
                String incremental = prefixDelta(lastStep, token.text());
                return incremental.isEmpty()
                        ? null
                        : StreamToken.stepDelta(token.stepId(), token.channel(), incremental);
            }
            if (token.isReasoning()) {
                String incremental = prefixDelta(lastReasoning, token.text());
                return incremental.isEmpty() ? null : StreamToken.reasoning(incremental);
            }
            String incremental = prefixDelta(lastContent, token.text());
            return incremental.isEmpty() ? null : StreamToken.content(incremental, token.afterStepId());
        });
    }

    public static Flux<String> normalizeText(Flux<String> source) {
        AtomicReference<String> last = new AtomicReference<>("");
        return source.mapNotNull(text -> {
            String incremental = prefixDelta(last, text);
            return incremental.isEmpty() ? null : incremental;
        });
    }

    /** 单调前缀续写：incoming 必须以 prev 为前缀，否则视为独立增量块 */
    static String prefixDelta(AtomicReference<String> lastFull, String incoming) {
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
            return "";
        }
        lastFull.set(prev + incoming);
        return incoming;
    }
}
