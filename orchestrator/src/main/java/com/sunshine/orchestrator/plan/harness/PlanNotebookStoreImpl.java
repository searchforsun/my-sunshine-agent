package com.sunshine.orchestrator.plan.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlanNotebookStoreImpl implements PlanNotebookStore {
    private final StringRedisTemplate redis;
    private final AgentExecutionProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void save(PlanNotebook notebook) {
        if (notebook == null || !StringUtils.hasText(notebook.getSessionId())) return;
        try {
            String key = key(notebook.getSessionId());
            redis.opsForValue().set(key, objectMapper.writeValueAsString(notebook), ttl());
        } catch (Exception e) {
            log.warn("[PlanNotebook] save failed sessionId={}: {}", notebook.getSessionId(), e.getMessage());
        }
    }

    @Override
    public Optional<PlanNotebook> load(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return Optional.empty();
        String key = key(sessionId);
        try {
            String json = redis.opsForValue().get(key);
            if (!StringUtils.hasText(json)) return Optional.empty();
            PlanNotebook notebook = objectMapper.readValue(json, PlanNotebook.class);
            repairInProgressTasks(notebook);
            return Optional.of(notebook);
        } catch (Exception e) {
            log.warn("[PlanNotebook] load failed key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return;
        try {
            redis.delete(key(sessionId));
        } catch (Exception e) {
            log.warn("[PlanNotebook] delete failed sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void renewTtl(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return;
        try {
            redis.expire(key(sessionId), ttl());
        } catch (Exception e) {
            log.warn("[PlanNotebook] renewTtl failed sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    static void repairInProgressTasks(PlanNotebook notebook) {
        Deque<TaskItem> queue = notebook.getTaskQueue();
        if (queue.isEmpty()) return;
        Deque<TaskItem> repaired = new ArrayDeque<>(queue.size());
        for (TaskItem item : queue) {
            if ("in_progress".equals(item.status())) {
                repaired.add(new TaskItem(
                        item.taskId(), item.label(), "fail",
                        item.dependsOn(), item.constraints(),
                        item.expectedOutput(), item.successCriteria()));
            } else {
                repaired.add(item);
            }
        }
        queue.clear();
        queue.addAll(repaired);
    }

    private String key(String sessionId) {
        return properties.getHarness().getNotebook().getKeyPrefix() + sessionId.strip();
    }

    private Duration ttl() {
        return Duration.ofSeconds(properties.getHarness().getNotebook().getRedisTtlSeconds());
    }
}
