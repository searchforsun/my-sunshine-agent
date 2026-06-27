package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.config.GenerationLockProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

/**
 * Redis 分布式锁：仅持锁实例执行 MySQL partial/final flush（Task 3.14）。
 * Key: sunshine:generation:lock:{generationId}，TTL 30s + flush 续期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedGenerationLock {

    private final StringRedisTemplate redis;
    private final GenerationLockProperties properties;
    private String ownerId;

    @PostConstruct
    public void initOwnerId() {
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // 测试/容器环境可能解析失败
        }
        ownerId = host + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[GenerationLock] ownerId={} enabled={} ttlSec={}",
                ownerId, properties.isEnabled(), properties.getTtlSec());
    }

    /** 创建 Job 后抢锁；false 表示其它实例已在 flush */
    public boolean tryAcquire(String generationId) {
        if (!properties.isEnabled() || generationId == null || generationId.isBlank()) {
            return true;
        }
        String key = key(generationId);
        Boolean ok = redis.opsForValue().setIfAbsent(
                key, ownerId, Duration.ofSeconds(properties.getTtlSec()));
        if (Boolean.TRUE.equals(ok)) {
            log.debug("[GenerationLock] acquired genId={}", generationId);
            return true;
        }
        String holder = redis.opsForValue().get(key);
        log.warn("[GenerationLock] acquire failed genId={} holder={}", generationId, holder);
        return false;
    }

    /** flush 前校验并续期 TTL */
    public boolean renewIfHeld(String generationId) {
        if (!properties.isEnabled() || generationId == null || generationId.isBlank()) {
            return true;
        }
        String key = key(generationId);
        String current = redis.opsForValue().get(key);
        if (!ownerId.equals(current)) {
            return false;
        }
        redis.expire(key, Duration.ofSeconds(properties.getTtlSec()));
        return true;
    }

    public boolean isHeldByThisInstance(String generationId) {
        if (!properties.isEnabled() || generationId == null || generationId.isBlank()) {
            return true;
        }
        return ownerId.equals(redis.opsForValue().get(key(generationId)));
    }

    public void release(String generationId) {
        if (!properties.isEnabled() || generationId == null || generationId.isBlank()) {
            return;
        }
        String key = key(generationId);
        String current = redis.opsForValue().get(key);
        if (ownerId.equals(current)) {
            redis.delete(key);
            log.debug("[GenerationLock] released genId={}", generationId);
        }
    }

    String ownerIdForTest() {
        return ownerId;
    }

    private String key(String generationId) {
        return properties.getKeyPrefix() + generationId;
    }
}
