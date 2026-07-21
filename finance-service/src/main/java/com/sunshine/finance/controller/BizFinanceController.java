package com.sunshine.finance.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.finance.dto.AdminExpenseRequest;
import com.sunshine.finance.dto.AdminExpenseVO;
import com.sunshine.finance.dto.AdminInboxRequest;
import com.sunshine.finance.dto.AdminInboxVO;
import com.sunshine.finance.service.FinanceBizService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 业务数据 Admin CRUD：鉴权 X-Admin-Token。 */
@RestController
@RequestMapping("/api/biz/finance")
@RequiredArgsConstructor
public class BizFinanceController {

    private final FinanceBizService financeBizService;

    @Value("${sunshine.biz.admin-token:sunshine-mock-admin-dev}")
    private String adminToken;

    @GetMapping("/expenses")
    public R<List<AdminExpenseVO>> listExpenses(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "status", required = false) String status) {
        requireAdmin(token);
        return R.ok(financeBizService.adminListExpenses(tenantId, userId, status));
    }

    @PostMapping("/expenses")
    public R<AdminExpenseVO> createExpense(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody AdminExpenseRequest request) {
        requireAdmin(token);
        return R.ok(financeBizService.adminCreateExpense(request));
    }

    @PutMapping("/expenses/{id}")
    public R<AdminExpenseVO> updateExpense(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody AdminExpenseRequest request) {
        requireAdmin(token);
        return R.ok(financeBizService.adminUpdateExpense(id, request));
    }

    @DeleteMapping("/expenses/{id}")
    public R<Map<String, String>> deleteExpense(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id) {
        requireAdmin(token);
        financeBizService.adminDeleteExpense(id);
        return R.ok(Map.of("id", id, "status", "deleted"));
    }

    @GetMapping("/inbox")
    public R<List<AdminInboxVO>> listInbox(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "status", required = false) String status) {
        requireAdmin(token);
        return R.ok(financeBizService.adminListInbox(tenantId, userId, status));
    }

    @PostMapping("/inbox")
    public R<AdminInboxVO> createInbox(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody AdminInboxRequest request) {
        requireAdmin(token);
        return R.ok(financeBizService.adminCreateInbox(request));
    }

    @PutMapping("/inbox/{id}")
    public R<AdminInboxVO> updateInbox(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody AdminInboxRequest request) {
        requireAdmin(token);
        return R.ok(financeBizService.adminUpdateInbox(id, request));
    }

    @DeleteMapping("/inbox/{id}")
    public R<Map<String, String>> deleteInbox(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id) {
        requireAdmin(token);
        financeBizService.adminDeleteInbox(id);
        return R.ok(Map.of("id", id, "status", "deleted"));
    }

    private void requireAdmin(String token) {
        if (!StringUtils.hasText(token) || !adminToken.equals(token)) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
