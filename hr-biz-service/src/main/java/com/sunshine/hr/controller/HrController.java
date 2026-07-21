package com.sunshine.hr.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.hr.dto.SubmitLeaveRequest;
import com.sunshine.hr.exception.HrErrorCode;
import com.sunshine.hr.model.AttendanceMonth;
import com.sunshine.hr.model.LeaveBalance;
import com.sunshine.hr.model.LeaveRequest;
import com.sunshine.hr.store.HrTenantUserStore;
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
@RequestMapping("/api/hr")
@RequiredArgsConstructor
public class HrController {

    private final HrTenantUserStore store;

    @GetMapping("/leave/balance")
    public R<LeaveBalance> getLeaveBalance(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestParam(value = "year", required = false) Integer year) {
        String uid = requireUser(userId);
        return R.ok(store.getLeaveBalance(tenantOrDefault(tenantId), uid, year)
                .orElse(null));
    }

    @GetMapping("/leave/requests")
    public R<List<LeaveRequest>> listLeaveRequests(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestParam(value = "status", required = false) String status) {
        String uid = requireUser(userId);
        return R.ok(store.listLeaveRequests(tenantOrDefault(tenantId), uid, status));
    }

    @PostMapping("/leave/requests")
    public R<LeaveRequest> submitLeave(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestBody SubmitLeaveRequest request) {
        String uid = requireUser(userId);
        if (request == null || !StringUtils.hasText(request.leaveType())
                || !StringUtils.hasText(request.startDate())
                || !StringUtils.hasText(request.endDate())
                || !StringUtils.hasText(request.reason())) {
            throw new BizException(HrErrorCode.INVALID_LEAVE_REQUEST);
        }
        return R.ok(store.submitLeaveRequest(
                tenantOrDefault(tenantId),
                uid,
                request.leaveType().trim(),
                request.startDate().trim(),
                request.endDate().trim(),
                request.reason().trim()));
    }

    @GetMapping("/attendance/{yearMonth}")
    public R<AttendanceMonth> getAttendance(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @PathVariable String yearMonth) {
        String uid = requireUser(userId);
        return R.ok(store.getAttendanceMonth(tenantOrDefault(tenantId), uid, yearMonth)
                .orElseThrow(() -> new BizException(HrErrorCode.ATTENDANCE_NOT_FOUND)));
    }

    private static String requireUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BizException(HrErrorCode.USER_REQUIRED);
        }
        return userId.trim();
    }

    private static String tenantOrDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }
}
