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
 * 对话级沙箱会话 Redis 绑定 — key {@code sandbox:conv:{tenant}:{conversationId}}。
 * ZSET {@code sandbox:conv:expiry} → idle 停机；{@code sandbox:conv:purge} → 销毁。
 * Key TTL 跟 purge（避免 idle 删 key 后无法 start）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSandboxStore {

    static final String KEY_PREFIX = "sandbox:conv:";
    static final String EXPIRY_ZSET = "sandbox:conv:expiry";
    static final String PURGE_ZSET = "sandbox:conv:purge";

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

    /** 保存并刷新 idle + purge（活动续期） */
    public void save(ConversationSandboxBinding binding) {
        if (binding == null || !StringUtils.hasText(binding.conversationId())) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        long purgeAt = now + purgeMs();
        ConversationSandboxBinding toStore = binding
                .withState(StringUtils.hasText(binding.state()) ? binding.state() : ConversationSandboxBinding.STATE_RUNNING)
                .withPurgeAt(purgeAt);
        writeAll(toStore, now + idleMs(), purgeAt);
    }

    public void touch(String tenantId, String conversationId) {
        find(tenantId, conversationId).ifPresent(b -> save(b.withState(ConversationSandboxBinding.STATE_RUNNING)));
    }

    /** idle Reaper：标记 stopped，移出 expiry，保留 purge */
    public void markStopped(String tenantId, String conversationId) {
        Optional<ConversationSandboxBinding> existing = find(tenantId, conversationId);
        if (existing.isEmpty()) {
            return;
        }
        ConversationSandboxBinding b = existing.get().withState(ConversationSandboxBinding.STATE_STOPPED);
        long purgeAt = b.purgeAtEpochMs() != null ? b.purgeAtEpochMs() : Instant.now().toEpochMilli() + purgeMs();
        ConversationSandboxBinding stopped = b.withPurgeAt(purgeAt);
        try {
            String k = key(stopped.tenantId(), stopped.conversationId());
            long ttlMs = Math.max(60_000L, purgeAt - Instant.now().toEpochMilli());
            redis.opsForValue().set(k, objectMapper.writeValueAsString(stopped), Duration.ofMillis(ttlMs));
            redis.opsForZSet().remove(EXPIRY_ZSET, member(stopped));
            redis.opsForZSet().add(PURGE_ZSET, member(stopped), purgeAt);
        } catch (Exception e) {
            log.warn("[SandboxConv] markStopped failed: {}", e.getMessage());
        }
    }

    public Optional<ConversationSandboxBinding> remove(String tenantId, String conversationId) {
        Optional<ConversationSandboxBinding> existing = find(tenantId, conversationId);
        String k = key(tenantId, conversationId);
        try {
            redis.delete(k);
            existing.ifPresent(b -> {
                redis.opsForZSet().remove(EXPIRY_ZSET, member(b));
                redis.opsForZSet().remove(PURGE_ZSET, member(b));
            });
        } catch (Exception e) {
            log.warn("[SandboxConv] Redis remove failed key={}: {}", k, e.getMessage());
        }
        return existing;
    }

    public Set<String> pollExpiredMembers(long nowEpochMs) {
        return rangeByScore(EXPIRY_ZSET, nowEpochMs);
    }

    public Set<String> pollPurgeMembers(long nowEpochMs) {
        return rangeByScore(PURGE_ZSET, nowEpochMs);
    }

    public void removeExpiryMember(String member) {
        removeZMember(EXPIRY_ZSET, member);
    }

    public void removePurgeMember(String member) {
        removeZMember(PURGE_ZSET, member);
    }

    public static String[] splitMember(String member) {
        if (!StringUtils.hasText(member)) {
            return new String[0];
        }
        return member.split("\\|", 3);
    }

    private void writeAll(ConversationSandboxBinding binding, long expiryScore, long purgeScore) {
        String k = key(binding.tenantId(), binding.conversationId());
        try {
            long ttlMs = Math.max(60_000L, purgeMs() + 60_000L);
            redis.opsForValue().set(k, objectMapper.writeValueAsString(binding), Duration.ofMillis(ttlMs));
            redis.opsForZSet().add(EXPIRY_ZSET, member(binding), expiryScore);
            redis.opsForZSet().add(PURGE_ZSET, member(binding), purgeScore);
        } catch (Exception e) {
            log.warn("[SandboxConv] Redis save failed key={}: {}", k, e.getMessage());
        }
    }

    private Set<String> rangeByScore(String zset, long nowEpochMs) {
        try {
            Set<String> members = redis.opsForZSet().rangeByScore(zset, 0, nowEpochMs);
            return members != null ? members : Set.of();
        } catch (Exception e) {
            log.warn("[SandboxConv] {} poll failed: {}", zset, e.getMessage());
            return Set.of();
        }
    }

    private void removeZMember(String zset, String member) {
        try {
            redis.opsForZSet().remove(zset, member);
        } catch (Exception e) {
            log.warn("[SandboxConv] {} remove failed: {}", zset, e.getMessage());
        }
    }

    private long idleMs() {
        return Math.max(60L, properties.getConversationTtlSec()) * 1000L;
    }

    private long purgeMs() {
        return Math.max(idleMs(), (long) Math.max(60, properties.getPurgeTtlSec()) * 1000L);
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
