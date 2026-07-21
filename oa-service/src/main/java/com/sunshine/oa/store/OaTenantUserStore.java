package com.sunshine.oa.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.oa.model.OaTask;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按 (tenantId, userId) 隔离的 OA 待办种子数据。启动加载 classpath:mock/seed-users.json。
 */
@Component
public class OaTenantUserStore {

    private static final String SEED_PATH = "mock/seed-users.json";

    private final ObjectMapper objectMapper;
    /** tenantId → userId → UserData */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, UserData>> tenants = new ConcurrentHashMap<>();
    private Map<String, Map<String, SeedUser>> seedSnapshot = Map.of();

    public OaTenantUserStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        seedSnapshot = loadSeed();
        tenants.clear();
        for (String tenantId : seedSnapshot.keySet()) {
            reloadTenant(tenantId);
        }
    }

    public List<OaTask> listTasks(String tenantId, String userId, String status) {
        UserData data = userData(tenantId, userId);
        return filterByStatus(data.tasks, status, OaTask::status);
    }

    public Optional<OaTask> findTask(String tenantId, String userId, String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        return userData(tenantId, userId).tasks.stream()
                .filter(t -> taskId.equals(t.id()))
                .findFirst();
    }

    /**
     * 将当前用户名下 pending 任务标记为 done。
     * 非负责人或不存在 → empty。
     */
    public Optional<OaTask> approveTask(String tenantId, String userId, String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        String id = taskId.trim();
        UserData data = userData(tenantId, userId);
        for (int i = 0; i < data.tasks.size(); i++) {
            OaTask task = data.tasks.get(i);
            if (!id.equals(task.id())) {
                continue;
            }
            if (!userId.equals(task.assigneeUserId())) {
                return Optional.empty();
            }
            OaTask approved = new OaTask(
                    task.id(), task.title(), task.category(), "done", task.assigneeUserId());
            data.tasks.set(i, approved);
            return Optional.of(approved);
        }
        return Optional.empty();
    }

    /** 当前租户下已加载的 userId 列表（含种子用户）。 */
    public List<String> listUserIds(String tenantId) {
        ConcurrentHashMap<String, UserData> users = tenants.get(blankToDefault(tenantId));
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.keySet().stream().sorted().toList();
    }

    /** Admin 快照：tasks。 */
    public Map<String, Object> snapshot(String tenantId, String userId) {
        UserData data = userData(tenantId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId == null ? "" : userId.trim());
        out.put("tenantId", blankToDefault(tenantId));
        out.put("tasks", List.copyOf(data.tasks));
        return out;
    }

    /** 从 classpath 种子重载指定租户（清空该租户运行时写入）。 */
    public void reset(String tenantId) {
        String tenant = blankToDefault(tenantId);
        if (!seedSnapshot.containsKey(tenant)) {
            seedSnapshot = loadSeed();
        }
        reloadTenant(tenant);
    }

    private void reloadTenant(String tenantId) {
        ConcurrentHashMap<String, UserData> users = new ConcurrentHashMap<>();
        Map<String, SeedUser> seedUsers = seedSnapshot.getOrDefault(tenantId, Map.of());
        for (Map.Entry<String, SeedUser> entry : seedUsers.entrySet()) {
            users.put(entry.getKey(), UserData.fromSeed(entry.getValue()));
        }
        tenants.put(tenantId, users);
    }

    private UserData userData(String tenantId, String userId) {
        String tenant = blankToDefault(tenantId);
        String user = userId == null ? "" : userId.trim();
        return tenants
                .computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(user, u -> new UserData());
    }

    private Map<String, Map<String, SeedUser>> loadSeed() {
        try {
            ClassPathResource resource = new ClassPathResource(SEED_PATH);
            try (InputStream in = resource.getInputStream()) {
                return objectMapper.readValue(in, new TypeReference<>() {
                });
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + SEED_PATH, e);
        }
    }

    private static <T> List<T> filterByStatus(List<T> items, String status,
                                              java.util.function.Function<T, String> statusFn) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            return List.copyOf(items);
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return items.stream()
                .filter(i -> normalized.equals(statusFn.apply(i)))
                .toList();
    }

    private static String blankToDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }

    /** Jackson 反序列化种子用户结构 */
    public record SeedUser(List<OaTask> tasks) {
    }

    private static final class UserData {
        final CopyOnWriteArrayList<OaTask> tasks = new CopyOnWriteArrayList<>();

        static UserData fromSeed(SeedUser seed) {
            UserData data = new UserData();
            if (seed != null && seed.tasks() != null) {
                data.tasks.addAll(seed.tasks());
            }
            return data;
        }
    }
}
