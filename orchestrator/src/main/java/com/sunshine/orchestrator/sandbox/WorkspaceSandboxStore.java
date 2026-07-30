package com.sunshine.orchestrator.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentSandboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceSandboxStore {

    static final String KEY_PREFIX = "sandbox:ws:";
    static final String IDLE_ZSET = "sandbox:ws:idle";

    private final StringRedisTemplate redis;
    private final AgentSandboxProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<WorkspaceSandboxBinding> find(String tenantId, String workspaceId) {
        String key = key(tenantId, workspaceId);
        try {
            String json = redis.opsForValue().get(key);
            if (!StringUtils.hasText(json)) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, WorkspaceSandboxBinding.class));
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] Redis get failed key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(WorkspaceSandboxBinding binding) {
        if (binding == null || !StringUtils.hasText(binding.workspaceId())) return;
        long now = Instant.now().toEpochMilli();
        WorkspaceSandboxBinding toStore = binding.withState(
                StringUtils.hasText(binding.state()) ? binding.state() : WorkspaceSandboxBinding.STATE_RUNNING);
        try {
            String k = key(toStore.tenantId(), toStore.workspaceId());
            redis.opsForValue().set(k, objectMapper.writeValueAsString(toStore));
            redis.opsForZSet().add(IDLE_ZSET, member(toStore), now + idleMs());
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] save failed: {}", e.getMessage());
        }
    }

    public void touch(String tenantId, String workspaceId) {
        find(tenantId, workspaceId).ifPresent(b -> save(b.withState(WorkspaceSandboxBinding.STATE_RUNNING)));
    }

    public void markStopped(String tenantId, String workspaceId) {
        Optional<WorkspaceSandboxBinding> existing = find(tenantId, workspaceId);
        if (existing.isEmpty()) return;
        WorkspaceSandboxBinding b = existing.get().withState(WorkspaceSandboxBinding.STATE_STOPPED);
        try {
            String k = key(b.tenantId(), b.workspaceId());
            redis.opsForValue().set(k, objectMapper.writeValueAsString(b));
            redis.opsForZSet().remove(IDLE_ZSET, member(b));
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] markStopped failed: {}", e.getMessage());
        }
    }

    public Optional<WorkspaceSandboxBinding> remove(String tenantId, String workspaceId) {
        Optional<WorkspaceSandboxBinding> existing = find(tenantId, workspaceId);
        String k = key(tenantId, workspaceId);
        try {
            redis.delete(k);
            existing.ifPresent(b -> redis.opsForZSet().remove(IDLE_ZSET, member(b)));
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] remove failed key={}: {}", k, e.getMessage());
        }
        return existing;
    }

    public Set<String> pollIdleMembers(long nowEpochMs) {
        return rangeByScore(IDLE_ZSET, nowEpochMs);
    }

    public void removeIdleMember(String member) {
        removeZMember(IDLE_ZSET, member);
    }

    public static String[] splitMember(String member) {
        if (!StringUtils.hasText(member)) return new String[0];
        return member.split("\\|", 3);
    }

    private Set<String> rangeByScore(String zset, long nowEpochMs) {
        try {
            Set<String> members = redis.opsForZSet().rangeByScore(zset, 0, nowEpochMs);
            return members != null ? members : Set.of();
        } catch (Exception e) {
            log.warn("[WorkspaceSandbox] {} poll failed: {}", zset, e.getMessage());
            return Set.of();
        }
    }

    private void removeZMember(String zset, String member) {
        try { redis.opsForZSet().remove(zset, member); }
        catch (Exception e) { log.warn("[WorkspaceSandbox] remove failed: {}", e.getMessage()); }
    }

    private long idleMs() {
        return Math.max(60L, properties.getConversationTtlSec()) * 1000L;
    }

    static String key(String tenantId, String workspaceId) {
        String tenant = StringUtils.hasText(tenantId) ? tenantId.strip() : "default";
        return KEY_PREFIX + tenant + ":" + workspaceId.strip();
    }

    static String member(WorkspaceSandboxBinding b) {
        return b.sessionId() + "|" + (StringUtils.hasText(b.tenantId()) ? b.tenantId() : "default")
                + "|" + b.workspaceId();
    }
}
