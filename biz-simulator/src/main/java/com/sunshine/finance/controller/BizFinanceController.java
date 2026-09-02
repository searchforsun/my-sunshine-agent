package com.sunshine.finance.controller;

import com.sunshine.common.biz.BizAdminAuth;
import com.sunshine.common.core.result.R;
import com.sunshine.finance.dto.AdminExpenseRequest;
import com.sunshine.finance.dto.AdminExpenseVO;
import com.sunshine.finance.dto.AdminInboxRequest;
import com.sunshine.finance.dto.AdminInboxVO;
import com.sunshine.finance.service.FinanceBizService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

/** 业务数据 Admin CRUD：鉴权 {@link BizAdminAuth#HEADER}。 */
@RestController
@RequestMapping("/api/biz/finance")
@RequiredArgsConstructor
public class BizFinanceController {

    private final FinanceBizService financeBizService;

    @Value(BizAdminAuth.TOKEN_PROPERTY)
    private String adminToken;

    @GetMapping("/expenses")
    public R<List<AdminExpenseVO>> listExpenses(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "status", required = false) String status) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(financeBizService.adminListExpenses(tenantId, userId, status));
    }

    @PostMapping("/expenses")
    public R<AdminExpenseVO> createExpense(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestBody AdminExpenseRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(financeBizService.adminCreateExpense(request));
    }

    @PutMapping("/expenses/{id}")
    public R<AdminExpenseVO> updateExpense(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String id,
            @RequestBody AdminExpenseRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(financeBizService.adminUpdateExpense(id, request));
    }

    @DeleteMapping("/expenses/{id}")
    public R<Map<String, String>> deleteExpense(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String id) {
        BizAdminAuth.require(token, adminToken);
        financeBizService.adminDeleteExpense(id);
        return R.ok(Map.of("id", id, "status", "deleted"));
    }

    @GetMapping("/inbox")
    public R<List<AdminInboxVO>> listInbox(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "status", required = false) String status) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(financeBizService.adminListInbox(tenantId, userId, status));
    }

    @PostMapping("/inbox")
    public R<AdminInboxVO> createInbox(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestBody AdminInboxRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(financeBizService.adminCreateInbox(request));
    }

    @PutMapping("/inbox/{id}")
    public R<AdminInboxVO> updateInbox(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String id,
            @RequestBody AdminInboxRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(financeBizService.adminUpdateInbox(id, request));
    }

    @DeleteMapping("/inbox/{id}")
    public R<Map<String, String>> deleteInbox(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String id) {
        BizAdminAuth.require(token, adminToken);
        financeBizService.adminDeleteInbox(id);
        return R.ok(Map.of("id", id, "status", "deleted"));
    }
}
