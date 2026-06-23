package com.sunshine.llm.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * 语义缓存 — 对 (model, messages, temperature) 做 MD5 哈希
 * 配合 DeepSeek V4 缓存命中定价（¥0.02/M tokens），成本可降 98%+
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String CACHE_PREFIX = "llm:cache:";
    private static final Duration TTL = Duration.ofHours(24);

    public Mono<ChatCompletionResponse> get(ChatCompletionRequest request) {
        if (Boolean.TRUE.equals(request.getSkipCache())) {
            return Mono.empty();
        }
        String key = cacheKey(request);

        return redisTemplate.opsForValue()
                .get(key)
                .flatMap(json -> {
                    try {
                        ChatCompletionResponse response = objectMapper.readValue(
                                json, ChatCompletionResponse.class);
                        if (hasEmptyAssistantContent(response)) {
                            log.warn("[LLM-GW] 忽略空 content 缓存: key={}", key);
                            return redisTemplate.delete(key).then(Mono.empty());
                        }
                        log.debug("[LLM-GW] 缓存命中: key={}", key);
                        return Mono.just(response);
                    } catch (JsonProcessingException e) {
                        log.warn("[LLM-GW] 缓存反序列化失败: {}", e.getMessage());
                        return Mono.empty();
                    }
                });
    }

    public Mono<Boolean> put(ChatCompletionRequest request, ChatCompletionResponse response) {
        if (Boolean.TRUE.equals(request.getSkipCache()) || hasEmptyAssistantContent(response)) {
            return Mono.just(false);
        }
        String key = cacheKey(request);
        try {
            String json = objectMapper.writeValueAsString(response);
            return redisTemplate.opsForValue()
                    .set(key, json, TTL)
                    .doOnSuccess(ok -> log.debug("[LLM-GW] 缓存写入: key={}", key));
        } catch (JsonProcessingException e) {
            log.warn("[LLM-GW] 缓存序列化失败: {}", e.getMessage());
            return Mono.just(false);
        }
    }

    private static boolean hasEmptyAssistantContent(ChatCompletionResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return true;
        }
        ChatCompletionResponse.Message message = response.getChoices().get(0).getMessage();
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            return true;
        }
        String trimmed = message.getContent().strip();
        if (trimmed.startsWith("{") && !isCompleteJson(trimmed)) {
            log.warn("[LLM-GW] 忽略不完整 JSON 缓存");
            return true;
        }
        return false;
    }

    private static boolean isCompleteJson(String text) {
        try {
            new ObjectMapper().readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String cacheKey(ChatCompletionRequest request) {
        String raw = request.getModel() + "|"
                + request.getMessages().stream()
                        .map(m -> m.getRole() + ":" + m.getContent())
                        .reduce("", String::concat) + "|"
                + request.getTemperature();
        return CACHE_PREFIX + md5(raw);
    }

    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
