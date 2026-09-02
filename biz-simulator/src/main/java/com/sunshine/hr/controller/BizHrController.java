package com.sunshine.hr.controller;

import com.sunshine.common.biz.BizAdminAuth;
import com.sunshine.common.core.result.R;
import com.sunshine.hr.dto.AdminAttendanceMonthRequest;
import com.sunshine.hr.dto.AdminAttendanceMonthVO;
import com.sunshine.hr.dto.AdminLeaveBalanceRequest;
import com.sunshine.hr.dto.AdminLeaveBalanceVO;
import com.sunshine.hr.dto.AdminLeaveRequestRequest;
import com.sunshine.hr.dto.AdminLeaveRequestVO;
import com.sunshine.hr.service.HrBizService;
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
@RequestMapping("/api/biz/hr")
@RequiredArgsConstructor
public class BizHrController {

    private final HrBizService hrBizService;

    @Value(BizAdminAuth.TOKEN_PROPERTY)
    private String adminToken;

    @GetMapping("/leave-balances")
    public R<List<AdminLeaveBalanceVO>> listLeaveBalances(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminListLeaveBalances(tenantId, userId));
    }

    @PostMapping("/leave-balances")
    public R<AdminLeaveBalanceVO> createLeaveBalance(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestBody AdminLeaveBalanceRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminCreateLeaveBalance(request));
    }

    @PutMapping("/leave-balances/{userId}/{year}")
    public R<AdminLeaveBalanceVO> updateLeaveBalance(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String userId,
            @PathVariable Integer year,
            @RequestBody AdminLeaveBalanceRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminUpdateLeaveBalance(userId, year, request));
    }

    @DeleteMapping("/leave-balances/{userId}/{year}")
    public R<Map<String, Object>> deleteLeaveBalance(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String userId,
            @PathVariable Integer year,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId) {
        BizAdminAuth.require(token, adminToken);
        hrBizService.adminDeleteLeaveBalance(tenantId, userId, year);
        return R.ok(Map.of("userId", userId, "year", year, "status", "deleted"));
    }

    @GetMapping("/leave-requests")
    public R<List<AdminLeaveRequestVO>> listLeaveRequests(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "status", required = false) String status) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminListLeaveRequests(tenantId, userId, status));
    }

    @PostMapping("/leave-requests")
    public R<AdminLeaveRequestVO> createLeaveRequest(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestBody AdminLeaveRequestRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminCreateLeaveRequest(request));
    }

    @PutMapping("/leave-requests/{id}")
    public R<AdminLeaveRequestVO> updateLeaveRequest(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String id,
            @RequestBody AdminLeaveRequestRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminUpdateLeaveRequest(id, request));
    }

    @DeleteMapping("/leave-requests/{id}")
    public R<Map<String, String>> deleteLeaveRequest(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String id) {
        BizAdminAuth.require(token, adminToken);
        hrBizService.adminDeleteLeaveRequest(id);
        return R.ok(Map.of("id", id, "status", "deleted"));
    }

    @GetMapping("/attendance-months")
    public R<List<AdminAttendanceMonthVO>> listAttendanceMonths(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminListAttendanceMonths(tenantId, userId));
    }

    @PostMapping("/attendance-months")
    public R<AdminAttendanceMonthVO> createAttendanceMonth(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestBody AdminAttendanceMonthRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminCreateAttendanceMonth(request));
    }

    @PutMapping("/attendance-months/{userId}/{yearMonth}")
    public R<AdminAttendanceMonthVO> updateAttendanceMonth(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String userId,
            @PathVariable String yearMonth,
            @RequestBody AdminAttendanceMonthRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(hrBizService.adminUpdateAttendanceMonth(userId, yearMonth, request));
    }

    @DeleteMapping("/attendance-months/{userId}/{yearMonth}")
    public R<Map<String, String>> deleteAttendanceMonth(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String userId,
            @PathVariable String yearMonth,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId) {
        BizAdminAuth.require(token, adminToken);
        hrBizService.adminDeleteAttendanceMonth(tenantId, userId, yearMonth);
        return R.ok(Map.of("userId", userId, "yearMonth", yearMonth, "status", "deleted"));
    }
}
