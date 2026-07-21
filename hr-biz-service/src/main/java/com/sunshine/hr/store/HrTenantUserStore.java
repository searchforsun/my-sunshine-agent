package com.sunshine.hr.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.hr.model.AttendanceMonth;
import com.sunshine.hr.model.LeaveBalance;
import com.sunshine.hr.model.LeaveRequest;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按 (tenantId, userId) 隔离的 HR 内存种子数据。启动加载 classpath:mock/seed-users.json。
 */
@Component
public class HrTenantUserStore {

    private static final String SEED_PATH = "mock/seed-users.json";
    private static final int DEFAULT_YEAR = 2026;

    private final ObjectMapper objectMapper;
    /** tenantId → userId → UserData */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, UserData>> tenants = new ConcurrentHashMap<>();
    private Map<String, Map<String, SeedUser>> seedSnapshot = Map.of();

    public HrTenantUserStore(ObjectMapper objectMapper) {
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

    public Optional<LeaveBalance> getLeaveBalance(String tenantId, String userId, Integer year) {
        LeaveBalance balance = userData(tenantId, userId).leaveBalance;
        if (balance == null) {
            return Optional.empty();
        }
        int target = year != null ? year : DEFAULT_YEAR;
        if (balance.year() != target) {
            return Optional.empty();
        }
        return Optional.of(balance);
    }

    public List<LeaveRequest> listLeaveRequests(String tenantId, String userId, String status) {
        UserData data = userData(tenantId, userId);
        return filterByStatus(data.leaveRequests, status, LeaveRequest::status);
    }

    public LeaveRequest submitLeaveRequest(String tenantId, String userId,
                                           String leaveType, String startDate,
                                           String endDate, String reason) {
        String id = "leave-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        LeaveRequest record = new LeaveRequest(
                id,
                leaveType,
                startDate,
                endDate,
                reason,
                "pending");
        userData(tenantId, userId).leaveRequests.add(record);
        return record;
    }

    public Optional<AttendanceMonth> getAttendanceMonth(String tenantId, String userId, String yearMonth) {
        if (!StringUtils.hasText(yearMonth)) {
            return Optional.empty();
        }
        return Optional.ofNullable(userData(tenantId, userId).attendance.get(yearMonth.trim()));
    }

    /** 当前租户下已加载的 userId 列表（含种子用户）。 */
    public List<String> listUserIds(String tenantId) {
        ConcurrentHashMap<String, UserData> users = tenants.get(blankToDefault(tenantId));
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.keySet().stream().sorted().toList();
    }

    /** Admin 快照：leaveBalance + leaveRequests + attendance。 */
    public Map<String, Object> snapshot(String tenantId, String userId) {
        UserData data = userData(tenantId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId == null ? "" : userId.trim());
        out.put("tenantId", blankToDefault(tenantId));
        out.put("leaveBalance", data.leaveBalance);
        out.put("leaveRequests", List.copyOf(data.leaveRequests));
        out.put("attendance", Map.copyOf(data.attendance));
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
    public record SeedUser(
            LeaveBalance leaveBalance,
            List<LeaveRequest> leaveRequests,
            Map<String, AttendanceMonth> attendance
    ) {
    }

    private static final class UserData {
        LeaveBalance leaveBalance;
        final CopyOnWriteArrayList<LeaveRequest> leaveRequests = new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<String, AttendanceMonth> attendance = new ConcurrentHashMap<>();

        static UserData fromSeed(SeedUser seed) {
            UserData data = new UserData();
            if (seed == null) {
                return data;
            }
            data.leaveBalance = seed.leaveBalance();
            if (seed.leaveRequests() != null) {
                data.leaveRequests.addAll(seed.leaveRequests());
            }
            if (seed.attendance() != null) {
                data.attendance.putAll(seed.attendance());
            }
            return data;
        }
    }
}
