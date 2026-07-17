package com.sunshine.orchestrator.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 对话级沙箱会话 Redis 绑定 — key {@code sandbox:conv:{tenant}:{conversationId}}，
 * ZSET {@code sandbox:conv:expiry} 供 Reaper 关闭过期容器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSandboxStore {

    static final String KEY_PREFIX = "sandbox:conv:";
    static final String EXPIRY_ZSET = "sandbox:conv:expiry";

    private final StringRedisTemplate redis;
    private final AgentSandboxProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<ConversationSandboxBinding> find(String tenantId, String conversationId) {
        String key = key(tenantId, conversationId);
        try {
            String json = redis.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ConversationSandboxBinding.class));
        } catch (Exception e) {
            log.warn("[SandboxConv] Redis get failed key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(ConversationSandboxBinding binding) {
        if (binding == null || !StringUtils.hasText(binding.conversationId())) {
            return;
        }
        String key = key(binding.tenantId(), binding.conversationId());
        Duration ttl = ttl();
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(binding), ttl);
            long score = Instant.now().plus(ttl).toEpochMilli();
            redis.opsForZSet().add(EXPIRY_ZSET, member(binding), score);
        } catch (Exception e) {
            log.warn("[SandboxConv] Redis save failed key={}: {}", key, e.getMessage());
        }
    }

    public void touch(String tenantId, String conversationId) {
        find(tenantId, conversationId).ifPresent(this::save);
    }

    public Optional<ConversationSandboxBinding> remove(String tenantId, String conversationId) {
        Optional<ConversationSandboxBinding> existing = find(tenantId, conversationId);
        String key = key(tenantId, conversationId);
        try {
            redis.delete(key);
            existing.ifPresent(b -> redis.opsForZSet().remove(EXPIRY_ZSET, member(b)));
        } catch (Exception e) {
            log.warn("[SandboxConv] Redis remove failed key={}: {}", key, e.getMessage());
        }
        return existing;
    }

    /** 到期成员：sessionId|tenantId|conversationId */
    public Set<String> pollExpiredMembers(long nowEpochMs) {
        try {
            Set<String> members = redis.opsForZSet().rangeByScore(EXPIRY_ZSET, 0, nowEpochMs);
            return members != null ? members : Set.of();
        } catch (Exception e) {
            log.warn("[SandboxConv] expiry poll failed: {}", e.getMessage());
            return Set.of();
        }
    }

    public void removeExpiryMember(String member) {
        try {
            redis.opsForZSet().remove(EXPIRY_ZSET, member);
        } catch (Exception e) {
            log.warn("[SandboxConv] expiry remove failed: {}", e.getMessage());
        }
    }

    public static String[] splitMember(String member) {
        if (!StringUtils.hasText(member)) {
            return new String[0];
        }
        return member.split("\\|", 3);
    }

    private Duration ttl() {
        int sec = Math.max(60, properties.getConversationTtlSec());
        return Duration.ofSeconds(sec);
    }

    static String key(String tenantId, String conversationId) {
        String tenant = StringUtils.hasText(tenantId) ? tenantId.strip() : "default";
        return KEY_PREFIX + tenant + ":" + conversationId.strip();
    }

    static String member(ConversationSandboxBinding b) {
        return b.sessionId() + "|" + (StringUtils.hasText(b.tenantId()) ? b.tenantId() : "default")
                + "|" + b.conversationId();
    }
}
