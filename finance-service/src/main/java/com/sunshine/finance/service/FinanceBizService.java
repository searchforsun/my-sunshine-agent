package com.sunshine.finance.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.finance.dto.AdminExpenseRequest;
import com.sunshine.finance.dto.AdminExpenseVO;
import com.sunshine.finance.dto.AdminInboxRequest;
import com.sunshine.finance.dto.AdminInboxVO;
import com.sunshine.finance.dto.ExpenseSummaryVO;
import com.sunshine.finance.entity.FinExpenseEntity;
import com.sunshine.finance.entity.FinInboxEntity;
import com.sunshine.finance.exception.FinanceErrorCode;
import com.sunshine.finance.model.ExpenseRecord;
import com.sunshine.finance.model.FinanceInboxItem;
import com.sunshine.finance.repo.FinExpenseRepository;
import com.sunshine.finance.repo.FinInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FinanceBizService {

    private final FinExpenseRepository expenseRepository;
    private final FinInboxRepository inboxRepository;

    @Transactional(readOnly = true)
    public List<ExpenseRecord> listExpenses(String tenantId, String userId, String status) {
        String tenant = blankToDefault(tenantId);
        String user = blankToEmpty(userId);
        List<FinExpenseEntity> rows;
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            rows = expenseRepository.findByTenantIdAndUserIdOrderByIdAsc(tenant, user);
        } else {
            rows = expenseRepository.findByTenantIdAndUserIdAndStatusOrderByIdAsc(
                    tenant, user, status.trim().toLowerCase(Locale.ROOT));
        }
        return rows.stream().map(this::toExpenseRecord).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ExpenseRecord> findExpense(String tenantId, String userId, String expenseId) {
        if (!StringUtils.hasText(expenseId)) {
            return Optional.empty();
        }
        return expenseRepository
                .findByTenantIdAndUserIdAndId(blankToDefault(tenantId), blankToEmpty(userId), expenseId.trim())
                .map(this::toExpenseRecord);
    }

    @Transactional
    public ExpenseRecord submitExpense(String tenantId, String userId,
                                       String category, BigDecimal amount,
                                       String occurredOn, String remark) {
        Instant now = Instant.now();
        FinExpenseEntity entity = new FinExpenseEntity();
        entity.setId("exp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        entity.setTenantId(blankToDefault(tenantId));
        entity.setUserId(blankToEmpty(userId));
        entity.setCategory(category);
        entity.setAmount(amount);
        entity.setStatus("pending");
        entity.setOccurredOn(LocalDate.parse(occurredOn));
        entity.setRemark(remark);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toExpenseRecord(expenseRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<FinanceInboxItem> listInbox(String tenantId, String userId, String status) {
        String tenant = blankToDefault(tenantId);
        String user = blankToEmpty(userId);
        List<FinInboxEntity> rows;
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            rows = inboxRepository.findByTenantIdAndUserIdOrderByIdAsc(tenant, user);
        } else {
            rows = inboxRepository.findByTenantIdAndUserIdAndStatusOrderByIdAsc(
                    tenant, user, status.trim().toLowerCase(Locale.ROOT));
        }
        return rows.stream().map(this::toInboxItem).toList();
    }

    @Transactional(readOnly = true)
    public Optional<FinanceInboxItem> findInboxItem(String tenantId, String userId, String itemId) {
        if (!StringUtils.hasText(itemId)) {
            return Optional.empty();
        }
        return inboxRepository
                .findByTenantIdAndUserIdAndId(blankToDefault(tenantId), blankToEmpty(userId), itemId.trim())
                .map(this::toInboxItem);
    }

    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
    public List<AdminExpenseVO> adminListExpenses(String tenantId, String userId, String status) {
        String tenant = blankToDefault(tenantId);
        boolean hasUser = StringUtils.hasText(userId);
        boolean hasStatus = StringUtils.hasText(status) && !"all".equalsIgnoreCase(status.trim());
        List<FinExpenseEntity> rows;
        if (hasUser && hasStatus) {
            rows = expenseRepository.findByTenantIdAndUserIdAndStatusOrderByIdAsc(
                    tenant, userId.trim(), status.trim().toLowerCase(Locale.ROOT));
        } else if (hasUser) {
            rows = expenseRepository.findByTenantIdAndUserIdOrderByIdAsc(tenant, userId.trim());
        } else if (hasStatus) {
            rows = expenseRepository.findByTenantIdAndStatusOrderByIdAsc(
                    tenant, status.trim().toLowerCase(Locale.ROOT));
        } else {
            rows = expenseRepository.findByTenantIdOrderByIdAsc(tenant);
        }
        return rows.stream().map(this::toAdminExpense).toList();
    }

    @Transactional
    public AdminExpenseVO adminCreateExpense(AdminExpenseRequest request) {
        requireExpenseRequest(request);
        Instant now = Instant.now();
        FinExpenseEntity entity = new FinExpenseEntity();
        entity.setId("exp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        entity.setTenantId(blankToDefault(request.tenantId()));
        entity.setUserId(request.userId().trim());
        entity.setCategory(request.category().trim());
        entity.setAmount(request.amount());
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toLowerCase(Locale.ROOT) : "pending");
        entity.setOccurredOn(LocalDate.parse(request.occurredOn().trim()));
        entity.setRemark(request.remark());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toAdminExpense(expenseRepository.save(entity));
    }

    @Transactional
    public AdminExpenseVO adminUpdateExpense(String id, AdminExpenseRequest request) {
        if (!StringUtils.hasText(id)) {
            throw new BizException(FinanceErrorCode.EXPENSE_NOT_FOUND);
        }
        requireExpenseRequest(request);
        FinExpenseEntity entity = expenseRepository.findById(id.trim())
                .orElseThrow(() -> new BizException(FinanceErrorCode.EXPENSE_NOT_FOUND));
        entity.setTenantId(blankToDefault(request.tenantId()));
        entity.setUserId(request.userId().trim());
        entity.setCategory(request.category().trim());
        entity.setAmount(request.amount());
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toLowerCase(Locale.ROOT) : entity.getStatus());
        entity.setOccurredOn(LocalDate.parse(request.occurredOn().trim()));
        entity.setRemark(request.remark());
        entity.setUpdatedAt(Instant.now());
        return toAdminExpense(expenseRepository.save(entity));
    }

    @Transactional
    public void adminDeleteExpense(String id) {
        if (!StringUtils.hasText(id) || !expenseRepository.existsById(id.trim())) {
            throw new BizException(FinanceErrorCode.EXPENSE_NOT_FOUND);
        }
        expenseRepository.deleteById(id.trim());
    }

    @Transactional(readOnly = true)
    public List<AdminInboxVO> adminListInbox(String tenantId, String userId, String status) {
        String tenant = blankToDefault(tenantId);
        boolean hasUser = StringUtils.hasText(userId);
        boolean hasStatus = StringUtils.hasText(status) && !"all".equalsIgnoreCase(status.trim());
        List<FinInboxEntity> rows;
        if (hasUser && hasStatus) {
            rows = inboxRepository.findByTenantIdAndUserIdAndStatusOrderByIdAsc(
                    tenant, userId.trim(), status.trim().toLowerCase(Locale.ROOT));
        } else if (hasUser) {
            rows = inboxRepository.findByTenantIdAndUserIdOrderByIdAsc(tenant, userId.trim());
        } else if (hasStatus) {
            rows = inboxRepository.findByTenantIdAndStatusOrderByIdAsc(
                    tenant, status.trim().toLowerCase(Locale.ROOT));
        } else {
            rows = inboxRepository.findByTenantIdOrderByIdAsc(tenant);
        }
        return rows.stream().map(this::toAdminInbox).toList();
    }

    @Transactional
    public AdminInboxVO adminCreateInbox(AdminInboxRequest request) {
        requireInboxRequest(request);
        Instant now = Instant.now();
        FinInboxEntity entity = new FinInboxEntity();
        entity.setId("inbox-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        entity.setTenantId(blankToDefault(request.tenantId()));
        entity.setUserId(request.userId().trim());
        entity.setTitle(request.title().trim());
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toLowerCase(Locale.ROOT) : "pending");
        entity.setAmount(request.amount());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toAdminInbox(inboxRepository.save(entity));
    }

    @Transactional
    public AdminInboxVO adminUpdateInbox(String id, AdminInboxRequest request) {
        if (!StringUtils.hasText(id)) {
            throw new BizException(FinanceErrorCode.INBOX_ITEM_NOT_FOUND);
        }
        requireInboxRequest(request);
        FinInboxEntity entity = inboxRepository.findById(id.trim())
                .orElseThrow(() -> new BizException(FinanceErrorCode.INBOX_ITEM_NOT_FOUND));
        entity.setTenantId(blankToDefault(request.tenantId()));
        entity.setUserId(request.userId().trim());
        entity.setTitle(request.title().trim());
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toLowerCase(Locale.ROOT) : entity.getStatus());
        entity.setAmount(request.amount());
        entity.setUpdatedAt(Instant.now());
        return toAdminInbox(inboxRepository.save(entity));
    }

    @Transactional
    public void adminDeleteInbox(String id) {
        if (!StringUtils.hasText(id) || !inboxRepository.existsById(id.trim())) {
            throw new BizException(FinanceErrorCode.INBOX_ITEM_NOT_FOUND);
        }
        inboxRepository.deleteById(id.trim());
    }

    private void requireExpenseRequest(AdminExpenseRequest request) {
        if (request == null || !StringUtils.hasText(request.userId())
                || !StringUtils.hasText(request.category())
                || request.amount() == null
                || !StringUtils.hasText(request.occurredOn())) {
            throw new BizException(FinanceErrorCode.INVALID_EXPENSE_REQUEST);
        }
    }

    private void requireInboxRequest(AdminInboxRequest request) {
        if (request == null || !StringUtils.hasText(request.userId())
                || !StringUtils.hasText(request.title())) {
            throw new BizException(FinanceErrorCode.INVALID_INBOX_REQUEST);
        }
    }

    private AdminExpenseVO toAdminExpense(FinExpenseEntity e) {
        return new AdminExpenseVO(
                e.getId(),
                e.getTenantId(),
                e.getUserId(),
                e.getCategory(),
                e.getAmount(),
                e.getStatus(),
                e.getOccurredOn() != null ? e.getOccurredOn().toString() : null,
                e.getRemark());
    }

    private AdminInboxVO toAdminInbox(FinInboxEntity e) {
        return new AdminInboxVO(
                e.getId(), e.getTenantId(), e.getUserId(), e.getTitle(), e.getStatus(), e.getAmount());
    }

    private ExpenseRecord toExpenseRecord(FinExpenseEntity e) {
        return new ExpenseRecord(
                e.getId(),
                e.getCategory(),
                e.getAmount(),
                e.getStatus(),
                e.getOccurredOn() != null ? e.getOccurredOn().toString() : null,
                e.getRemark());
    }

    private FinanceInboxItem toInboxItem(FinInboxEntity e) {
        return new FinanceInboxItem(e.getId(), e.getTitle(), e.getStatus(), e.getAmount());
    }

    private static ExpenseSummaryVO buildSummary(String status, List<ExpenseRecord> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (ExpenseRecord row : rows) {
            total = total.add(row.amount());
        }
        return new ExpenseSummaryVO(status, rows.size(), total);
    }

    private static String blankToDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }

    private static String blankToEmpty(String userId) {
        return userId == null ? "" : userId.trim();
    }
}
