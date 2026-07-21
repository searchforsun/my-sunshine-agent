package com.sunshine.finance.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.finance.dto.ExpenseSummaryVO;
import com.sunshine.finance.model.ExpenseRecord;
import com.sunshine.finance.model.FinanceInboxItem;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按 (tenantId, userId) 隔离的内存种子数据。启动加载 classpath:mock/seed-users.json。
 */
@Component
public class TenantUserStore {

    private static final String SEED_PATH = "mock/seed-users.json";

    private final ObjectMapper objectMapper;
    /** tenantId → userId → UserData */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, UserData>> tenants = new ConcurrentHashMap<>();
    private Map<String, Map<String, SeedUser>> seedSnapshot = Map.of();

    public TenantUserStore(ObjectMapper objectMapper) {
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

    public List<ExpenseRecord> listExpenses(String tenantId, String userId, String status) {
        UserData data = userData(tenantId, userId);
        return filterByStatus(data.expenses, status, ExpenseRecord::status);
    }

    public Optional<ExpenseRecord> findExpense(String tenantId, String userId, String expenseId) {
        if (!StringUtils.hasText(expenseId)) {
            return Optional.empty();
        }
        return userData(tenantId, userId).expenses.stream()
                .filter(e -> expenseId.equals(e.id()))
                .findFirst();
    }

    public ExpenseRecord submitExpense(String tenantId, String userId,
                                       String category, BigDecimal amount,
                                       String occurredOn, String remark) {
        String id = "exp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ExpenseRecord record = new ExpenseRecord(
                id,
                category,
                amount,
                "pending",
                occurredOn,
                remark);
        userData(tenantId, userId).expenses.add(record);
        return record;
    }

    public List<FinanceInboxItem> listInbox(String tenantId, String userId, String status) {
        UserData data = userData(tenantId, userId);
        return filterByStatus(data.inbox, status, FinanceInboxItem::status);
    }

    public Optional<FinanceInboxItem> findInboxItem(String tenantId, String userId, String itemId) {
        if (!StringUtils.hasText(itemId)) {
            return Optional.empty();
        }
        return userData(tenantId, userId).inbox.stream()
                .filter(i -> itemId.equals(i.id()))
                .findFirst();
    }

    public List<ExpenseSummaryVO> summarizeExpenses(String tenantId, String userId, String status) {
        List<ExpenseRecord> all = listExpenses(tenantId, userId, "all");
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            Map<String, List<ExpenseRecord>> byStatus = new LinkedHashMap<>();
            for (ExpenseRecord e : all) {
                byStatus.computeIfAbsent(e.status(), k -> new ArrayList<>()).add(e);
            }
            List<ExpenseSummaryVO> out = new ArrayList<>();
            for (Map.Entry<String, List<ExpenseRecord>> entry : byStatus.entrySet()) {
                out.add(buildSummary(entry.getKey(), entry.getValue()));
            }
            return out;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        List<ExpenseRecord> filtered = all.stream()
                .filter(e -> normalized.equals(e.status()))
                .toList();
        return List.of(buildSummary(normalized, filtered));
    }

    /** 当前租户下已加载的 userId 列表（含种子用户）。 */
    public List<String> listUserIds(String tenantId) {
        ConcurrentHashMap<String, UserData> users = tenants.get(blankToDefault(tenantId));
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.keySet().stream().sorted().toList();
    }

    /** Admin 快照：expenses + inbox。 */
    public Map<String, Object> snapshot(String tenantId, String userId) {
        UserData data = userData(tenantId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId == null ? "" : userId.trim());
        out.put("tenantId", blankToDefault(tenantId));
        out.put("expenses", List.copyOf(data.expenses));
        out.put("inbox", List.copyOf(data.inbox));
        return out;
    }

    /** 更新指定报销单状态；不存在则 empty。 */
    public Optional<ExpenseRecord> updateExpenseStatus(String tenantId, String userId,
                                                       String expenseId, String status) {
        if (!StringUtils.hasText(expenseId) || !StringUtils.hasText(status)) {
            return Optional.empty();
        }
        String id = expenseId.trim();
        String next = status.trim().toLowerCase(Locale.ROOT);
        UserData data = userData(tenantId, userId);
        for (int i = 0; i < data.expenses.size(); i++) {
            ExpenseRecord row = data.expenses.get(i);
            if (!id.equals(row.id())) {
                continue;
            }
            ExpenseRecord updated = new ExpenseRecord(
                    row.id(), row.category(), row.amount(), next, row.occurredOn(), row.remark());
            data.expenses.set(i, updated);
            return Optional.of(updated);
        }
        return Optional.empty();
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

    private static ExpenseSummaryVO buildSummary(String status, List<ExpenseRecord> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExpenseRecord row : rows) {
            total = total.add(row.amount());
        }
        return new ExpenseSummaryVO(status, rows.size(), total);
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
            List<ExpenseRecord> expenses,
            List<FinanceInboxItem> inbox
    ) {
    }

    private static final class UserData {
        final CopyOnWriteArrayList<ExpenseRecord> expenses = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<FinanceInboxItem> inbox = new CopyOnWriteArrayList<>();

        static UserData fromSeed(SeedUser seed) {
            UserData data = new UserData();
            if (seed != null && seed.expenses() != null) {
                data.expenses.addAll(seed.expenses());
            }
            if (seed != null && seed.inbox() != null) {
                data.inbox.addAll(seed.inbox());
            }
            return data;
        }
    }
}
