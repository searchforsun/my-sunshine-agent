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

    /** 无限等待时 Redis token 兜底 TTL（秒）：确认/取消/清理后仍会显式删除 */
    private static final long FALLBACK_REDIS_TTL_SEC = 604_800L;

    /** 注册待确认 token，返回 token 与阻塞 Future */
    public HitlRegistration register(String messageId, String toolId, String userId) {
        String token = UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waiters.put(token, new HitlPendingWaiter(messageId, toolId, userId, future));
        long expiresAt = resolveExpiresAt();
        storeToken(token, messageId, toolId, userId, expiresAt);
        return new HitlRegistration(token, future, expiresAt);
    }

    public int timeoutSec() {
        return properties.getTimeoutSec();
    }

    /** timeoutSec<=0 表示无限等待：expiresAt 无意义（前端不展示倒计时，确认仅按 token 定位） */
    private long resolveExpiresAt() {
        int timeoutSec = properties.getTimeoutSec();
        return timeoutSec <= 0 ? Long.MAX_VALUE : Instant.now().plusSeconds(timeoutSec).toEpochMilli();
    }

    private long resolveRedisTtlSec() {
        int timeoutSec = properties.getTimeoutSec();
        return timeoutSec <= 0 ? FALLBACK_REDIS_TTL_SEC : timeoutSec + 30L;
    }

    /** confirm-tool API（含发起用户身份校验） */
    public boolean confirm(String token, boolean approved, String currentUserId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        HitlPendingWaiter waiter = waiters.remove(token);
        if (waiter != null) {
            String userId = waiter.userId();
            if (userId != null && !userId.equals(currentUserId)) {
                log.warn("[HITL] 拒绝确认：发起用户 {} vs 当前用户 {} token={}", userId, currentUserId, token);
                return false;
            }
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

    /** confirm-tool API（无身份校验，仅用于内部降级） */
    public boolean confirm(String token, boolean approved) {
        return confirm(token, approved, null);
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

    private void storeToken(String token, String messageId, String toolId, String userId, long expiresAt) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("toolId", toolId);
        payload.put("userId", userId != null ? userId : "");
        payload.put("expiresAt", String.valueOf(expiresAt));
        try {
            redis.opsForValue().set(
                    redisKey(token),
                    objectMapper.writeValueAsString(payload),
                    Duration.ofSeconds(resolveRedisTtlSec()));
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
