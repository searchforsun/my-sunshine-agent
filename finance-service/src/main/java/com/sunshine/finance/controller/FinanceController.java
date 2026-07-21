package com.sunshine.finance.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.finance.dto.ExpenseSummaryVO;
import com.sunshine.finance.dto.SubmitExpenseRequest;
import com.sunshine.finance.exception.FinanceErrorCode;
import com.sunshine.finance.model.ExpenseRecord;
import com.sunshine.finance.model.FinanceInboxItem;
import com.sunshine.finance.store.TenantUserStore;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final TenantUserStore store;

    @GetMapping("/expenses")
    public R<List<ExpenseRecord>> listExpenses(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestParam(value = "status", required = false) String status) {
        String uid = requireUser(userId);
        return R.ok(store.listExpenses(tenantOrDefault(tenantId), uid, status));
    }

    @GetMapping("/expenses/summary")
    public R<List<ExpenseSummaryVO>> summarizeExpenses(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestParam(value = "status", required = false) String status) {
        String uid = requireUser(userId);
        return R.ok(store.summarizeExpenses(tenantOrDefault(tenantId), uid, status));
    }

    @GetMapping("/expenses/{expenseId}")
    public R<ExpenseRecord> getExpense(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @PathVariable String expenseId) {
        String uid = requireUser(userId);
        return R.ok(store.findExpense(tenantOrDefault(tenantId), uid, expenseId)
                .orElseThrow(() -> new BizException(FinanceErrorCode.EXPENSE_NOT_FOUND)));
    }

    @PostMapping("/expenses")
    public R<ExpenseRecord> submitExpense(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestBody SubmitExpenseRequest request) {
        String uid = requireUser(userId);
        if (request == null || !StringUtils.hasText(request.category())
                || request.amount() == null || !StringUtils.hasText(request.occurredOn())) {
            throw new BizException(FinanceErrorCode.INVALID_EXPENSE_REQUEST);
        }
        return R.ok(store.submitExpense(
                tenantOrDefault(tenantId),
                uid,
                request.category().trim(),
                request.amount(),
                request.occurredOn().trim(),
                request.remark()));
    }

    @GetMapping("/inbox")
    public R<List<FinanceInboxItem>> listInbox(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestParam(value = "status", required = false) String status) {
        String uid = requireUser(userId);
        return R.ok(store.listInbox(tenantOrDefault(tenantId), uid, status));
    }

    @GetMapping("/inbox/{itemId}")
    public R<FinanceInboxItem> getInboxItem(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @PathVariable String itemId) {
        String uid = requireUser(userId);
        return R.ok(store.findInboxItem(tenantOrDefault(tenantId), uid, itemId)
                .orElseThrow(() -> new BizException(FinanceErrorCode.INBOX_ITEM_NOT_FOUND)));
    }

    private static String requireUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BizException(FinanceErrorCode.USER_REQUIRED);
        }
        return userId.trim();
    }

    private static String tenantOrDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }
}
