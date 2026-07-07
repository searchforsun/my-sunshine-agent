package com.sunshine.orchestrator.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentHitlProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Redis token 元数据 + 内存 Future — 同实例阻塞唤醒 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HitlTokenRegistry {

    private static final String REDIS_KEY_PREFIX = "sunshine:hitl:pending:";

    private final AgentHitlProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, HitlPendingWaiter> waiters = new ConcurrentHashMap<>();

    /** 注册待确认 token，返回 token 与阻塞 Future */
    public HitlRegistration register(String messageId, String toolId) {
        String token = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waiters.put(token, new HitlPendingWaiter(messageId, toolId, future));
        long expiresAt = Instant.now().plusSeconds(properties.getTimeoutSec()).toEpochMilli();
        storeToken(token, messageId, toolId, expiresAt);
        return new HitlRegistration(token, future, expiresAt);
    }

    public int timeoutSec() {
        return properties.getTimeoutSec();
    }

    /** confirm-tool API */
    public boolean confirm(String token, boolean approved) {
        if (token == null || token.isBlank()) {
            return false;
        }
        HitlPendingWaiter waiter = waiters.remove(token);
        if (waiter != null) {
            waiter.future().complete(approved);
            redis.delete(redisKey(token));
            return true;
        }
        String key = redisKey(token);
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            log.warn("[HITL] confirm 无效 token={}", token);
            return false;
        }
        redis.delete(key);
        log.warn("[HITL] confirm token={} 无本地 waiter（可能已超时或其它实例）", token);
        return false;
    }

    public void cancelWaitersForMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String target = messageId.strip();
        waiters.entrySet().removeIf(entry -> {
            HitlPendingWaiter waiter = entry.getValue();
            if (!target.equals(waiter.messageId())) {
                return false;
            }
            waiter.future().cancel(true);
            redis.delete(redisKey(entry.getKey()));
            return true;
        });
    }

    public void cleanup(String token) {
        if (token != null) {
            waiters.remove(token);
            redis.delete(redisKey(token));
        }
    }

    private void storeToken(String token, String messageId, String toolId, long expiresAt) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("toolId", toolId);
        payload.put("expiresAt", String.valueOf(expiresAt));
        try {
            redis.opsForValue().set(
                    redisKey(token),
                    objectMapper.writeValueAsString(payload),
                    Duration.ofSeconds(properties.getTimeoutSec() + 30));
        } catch (JsonProcessingException e) {
            log.warn("[HITL] token 序列化失败: {}", e.getMessage());
        }
    }

    private static String redisKey(String token) {
        return REDIS_KEY_PREFIX + token;
    }

    public record HitlRegistration(String token, CompletableFuture<Boolean> future, long expiresAt) {
    }
}
