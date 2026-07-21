package com.sunshine.hr.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
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
@RequestMapping("/api/biz/hr")
@RequiredArgsConstructor
public class BizHrController {

    private final HrBizService hrBizService;

    @Value("${sunshine.biz.admin-token:sunshine-mock-admin-dev}")
    private String adminToken;

    @GetMapping("/leave-balances")
    public R<List<AdminLeaveBalanceVO>> listLeaveBalances(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId) {
        requireAdmin(token);
        return R.ok(hrBizService.adminListLeaveBalances(tenantId, userId));
    }

    @PostMapping("/leave-balances")
    public R<AdminLeaveBalanceVO> createLeaveBalance(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody AdminLeaveBalanceRequest request) {
        requireAdmin(token);
        return R.ok(hrBizService.adminCreateLeaveBalance(request));
    }

    @PutMapping("/leave-balances/{userId}/{year}")
    public R<AdminLeaveBalanceVO> updateLeaveBalance(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String userId,
            @PathVariable Integer year,
            @RequestBody AdminLeaveBalanceRequest request) {
        requireAdmin(token);
        return R.ok(hrBizService.adminUpdateLeaveBalance(userId, year, request));
    }

    @DeleteMapping("/leave-balances/{userId}/{year}")
    public R<Map<String, Object>> deleteLeaveBalance(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String userId,
            @PathVariable Integer year,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId) {
        requireAdmin(token);
        hrBizService.adminDeleteLeaveBalance(tenantId, userId, year);
        return R.ok(Map.of("userId", userId, "year", year, "status", "deleted"));
    }

    @GetMapping("/leave-requests")
    public R<List<AdminLeaveRequestVO>> listLeaveRequests(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "status", required = false) String status) {
        requireAdmin(token);
        return R.ok(hrBizService.adminListLeaveRequests(tenantId, userId, status));
    }

    @PostMapping("/leave-requests")
    public R<AdminLeaveRequestVO> createLeaveRequest(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody AdminLeaveRequestRequest request) {
        requireAdmin(token);
        return R.ok(hrBizService.adminCreateLeaveRequest(request));
    }

    @PutMapping("/leave-requests/{id}")
    public R<AdminLeaveRequestVO> updateLeaveRequest(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody AdminLeaveRequestRequest request) {
        requireAdmin(token);
        return R.ok(hrBizService.adminUpdateLeaveRequest(id, request));
    }

    @DeleteMapping("/leave-requests/{id}")
    public R<Map<String, String>> deleteLeaveRequest(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id) {
        requireAdmin(token);
        hrBizService.adminDeleteLeaveRequest(id);
        return R.ok(Map.of("id", id, "status", "deleted"));
    }

    @GetMapping("/attendance-months")
    public R<List<AdminAttendanceMonthVO>> listAttendanceMonths(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId) {
        requireAdmin(token);
        return R.ok(hrBizService.adminListAttendanceMonths(tenantId, userId));
    }

    @PostMapping("/attendance-months")
    public R<AdminAttendanceMonthVO> createAttendanceMonth(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody AdminAttendanceMonthRequest request) {
        requireAdmin(token);
        return R.ok(hrBizService.adminCreateAttendanceMonth(request));
    }

    @PutMapping("/attendance-months/{userId}/{yearMonth}")
    public R<AdminAttendanceMonthVO> updateAttendanceMonth(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String userId,
            @PathVariable String yearMonth,
            @RequestBody AdminAttendanceMonthRequest request) {
        requireAdmin(token);
        return R.ok(hrBizService.adminUpdateAttendanceMonth(userId, yearMonth, request));
    }

    @DeleteMapping("/attendance-months/{userId}/{yearMonth}")
    public R<Map<String, String>> deleteAttendanceMonth(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String userId,
            @PathVariable String yearMonth,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId) {
        requireAdmin(token);
        hrBizService.adminDeleteAttendanceMonth(tenantId, userId, yearMonth);
        return R.ok(Map.of("userId", userId, "yearMonth", yearMonth, "status", "deleted"));
    }

    private void requireAdmin(String token) {
        if (!StringUtils.hasText(token) || !adminToken.equals(token)) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
