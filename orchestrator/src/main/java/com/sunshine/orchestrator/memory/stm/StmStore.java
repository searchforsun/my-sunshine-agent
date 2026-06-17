package com.sunshine.orchestrator.memory.stm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * STM 热缓存 — Redis {@code sunshine:stm:{userId}:{convId}}，MySQL 仍为 SSOT。
 */
@Slf4j
@Component
@ConditionalOnBean(StringRedisTemplate.class)
@RequiredArgsConstructor
public class StmStore {

    private static final String KEY_PREFIX = "sunshine:stm:";

    private final StringRedisTemplate redis;
    private final MemoryProperties memoryProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<List<ChatTurn>> load(String userId, String convId) {
        try {
            String json = redis.opsForValue().get(key(userId, convId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            List<ChatTurn> turns = objectMapper.readValue(json, new TypeReference<>() {});
            return Optional.of(turns);
        } catch (Exception e) {
            log.warn("[STM] Redis 读取失败 conv={}: {}", convId, e.getMessage());
            return Optional.empty();
        }
    }

    public void replace(String userId, String convId, List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return;
        }
        try {
            redis.opsForValue().set(
                    key(userId, convId),
                    objectMapper.writeValueAsString(turns),
                    ttl());
        } catch (Exception e) {
            log.warn("[STM] Redis 写入失败 conv={}: {}", convId, e.getMessage());
        }
    }

    public void appendTurn(String userId, String convId, ChatTurn user, ChatTurn assistant) {
        List<ChatTurn> current = load(userId, convId).orElseGet(ArrayList::new);
        if (!(current instanceof ArrayList)) {
            current = new ArrayList<>(current);
        }
        if (user != null) {
            current.add(user);
        }
        if (assistant != null) {
            current.add(assistant);
        }
        replace(userId, convId, current);
    }

    private Duration ttl() {
        int hours = Math.max(1, memoryProperties.getStm().getRedisTtlHours());
        return Duration.ofHours(hours);
    }

    private static String key(String userId, String convId) {
        return KEY_PREFIX + userId + ":" + convId;
    }
}
