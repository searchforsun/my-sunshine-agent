package com.sunshine.orchestrator.taskboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** TaskBoard 热缓存 — Redis {@code react:taskboard:{assistantMsgId}} */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactTaskBoardStore {

    private static final String KEY_PREFIX = "react:taskboard:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<ReactTaskBoardState> load(String assistantMsgId) {
        if (assistantMsgId == null || assistantMsgId.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(assistantMsgId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ReactTaskBoardState.class));
        } catch (Exception e) {
            log.warn("[TaskBoard] Redis 读取失败 msg={}: {}", assistantMsgId, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(ReactTaskBoardState state) {
        if (state == null || state.assistantMsgId() == null || state.assistantMsgId().isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(
                    key(state.assistantMsgId()),
                    objectMapper.writeValueAsString(state),
                    TTL);
        } catch (Exception e) {
            log.warn("[TaskBoard] Redis 写入失败 msg={}: {}", state.assistantMsgId(), e.getMessage());
        }
    }

    private static String key(String assistantMsgId) {
        return KEY_PREFIX + assistantMsgId.strip();
    }
}
