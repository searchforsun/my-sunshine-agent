package com.sunshine.hr.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.hr.dto.AdminAttendanceMonthRequest;
import com.sunshine.hr.dto.AdminAttendanceMonthVO;
import com.sunshine.hr.dto.AdminLeaveBalanceRequest;
import com.sunshine.hr.dto.AdminLeaveBalanceVO;
import com.sunshine.hr.dto.AdminLeaveRequestRequest;
import com.sunshine.hr.dto.AdminLeaveRequestVO;
import com.sunshine.hr.entity.HrAttendanceMonthEntity;
import com.sunshine.hr.entity.HrAttendanceMonthId;
import com.sunshine.hr.entity.HrLeaveBalanceEntity;
import com.sunshine.hr.entity.HrLeaveBalanceId;
import com.sunshine.hr.entity.HrLeaveRequestEntity;
import com.sunshine.hr.exception.HrErrorCode;
import com.sunshine.hr.model.AttendanceMonth;
import com.sunshine.hr.model.LeaveBalance;
import com.sunshine.hr.model.LeaveRequest;
import com.sunshine.hr.repo.HrAttendanceMonthRepository;
import com.sunshine.hr.repo.HrLeaveBalanceRepository;
import com.sunshine.hr.repo.HrLeaveRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class HrBizService {

    private static final int DEFAULT_YEAR = 2026;

    private final HrLeaveBalanceRepository leaveBalanceRepository;
    private final HrLeaveRequestRepository leaveRequestRepository;
    private final HrAttendanceMonthRepository attendanceMonthRepository;

    @Transactional(readOnly = true)
    public Optional<LeaveBalance> getLeaveBalance(String tenantId, String userId, Integer year) {
        int target = year != null ? year : DEFAULT_YEAR;
        return leaveBalanceRepository
                .findByTenantIdAndUserIdAndYear(blankToDefault(tenantId), blankToEmpty(userId), target)
                .map(this::toLeaveBalance);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> listLeaveRequests(String tenantId, String userId, String status) {
        String tenant = blankToDefault(tenantId);
        String user = blankToEmpty(userId);
        List<HrLeaveRequestEntity> rows;
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            rows = leaveRequestRepository.findByTenantIdAndUserIdOrderByIdAsc(tenant, user);
        } else {
            rows = leaveRequestRepository.findByTenantIdAndUserIdAndStatusOrderByIdAsc(
                    tenant, user, status.trim().toLowerCase(Locale.ROOT));
        }
        return rows.stream().map(this::toLeaveRequest).toList();
    }

    @Transactional
    public LeaveRequest submitLeaveRequest(String tenantId, String userId,
                                           String leaveType, String startDate,
                                           String endDate, String reason) {
        Instant now = Instant.now();
        HrLeaveRequestEntity entity = new HrLeaveRequestEntity();
        entity.setId("leave-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        entity.setTenantId(blankToDefault(tenantId));
        entity.setUserId(blankToEmpty(userId));
        entity.setLeaveType(leaveType.trim().toLowerCase(Locale.ROOT));
        entity.setStartDate(LocalDate.parse(startDate.trim()));
        entity.setEndDate(LocalDate.parse(endDate.trim()));
        entity.setReason(reason.trim());
        entity.setStatus("pending");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toLeaveRequest(leaveRequestRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public Optional<AttendanceMonth> getAttendanceMonth(String tenantId, String userId, String yearMonth) {
        if (!StringUtils.hasText(yearMonth)) {
            return Optional.empty();
        }
        return attendanceMonthRepository
                .findByTenantIdAndUserIdAndYearMonth(
                        blankToDefault(tenantId), blankToEmpty(userId), yearMonth.trim())
                .map(this::toAttendanceMonth);
    }

    @Transactional(readOnly = true)
    public List<AdminLeaveBalanceVO> adminListLeaveBalances(String tenantId, String userId) {
        String tenant = blankToDefault(tenantId);
        List<HrLeaveBalanceEntity> rows = StringUtils.hasText(userId)
                ? leaveBalanceRepository.findByTenantIdAndUserIdOrderByYearAsc(tenant, userId.trim())
                : leaveBalanceRepository.findByTenantIdOrderByUserIdAscYearAsc(tenant);
        return rows.stream().map(this::toAdminLeaveBalance).toList();
    }

    @Transactional
    public AdminLeaveBalanceVO adminCreateLeaveBalance(AdminLeaveBalanceRequest request) {
        requireLeaveBalanceRequest(request);
        String tenant = blankToDefault(request.tenantId());
        String user = request.userId().trim();
        Integer year = request.year();
        HrLeaveBalanceId id = new HrLeaveBalanceId(tenant, user, year);
        if (leaveBalanceRepository.existsById(id)) {
            throw new BizException(HrErrorCode.INVALID_LEAVE_BALANCE);
        }
        Instant now = Instant.now();
        HrLeaveBalanceEntity entity = new HrLeaveBalanceEntity();
        entity.setTenantId(tenant);
        entity.setUserId(user);
        entity.setYear(year);
        entity.setAnnual(nullToZero(request.annual()));
        entity.setQingsong(nullToZero(request.qingsong()));
        entity.setCompensatory(nullToZero(request.compensatory()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toAdminLeaveBalance(leaveBalanceRepository.save(entity));
    }

    @Transactional
    public AdminLeaveBalanceVO adminUpdateLeaveBalance(String userId, Integer year, AdminLeaveBalanceRequest request) {
        if (!StringUtils.hasText(userId) || year == null) {
            throw new BizException(HrErrorCode.LEAVE_BALANCE_NOT_FOUND);
        }
        requireLeaveBalanceFields(request);
        String tenant = blankToDefault(request != null ? request.tenantId() : null);
        HrLeaveBalanceEntity entity = leaveBalanceRepository
                .findByTenantIdAndUserIdAndYear(tenant, userId.trim(), year)
                .orElseThrow(() -> new BizException(HrErrorCode.LEAVE_BALANCE_NOT_FOUND));
        entity.setAnnual(nullToZero(request.annual()));
        entity.setQingsong(nullToZero(request.qingsong()));
        entity.setCompensatory(nullToZero(request.compensatory()));
        entity.setUpdatedAt(Instant.now());
        return toAdminLeaveBalance(leaveBalanceRepository.save(entity));
    }

    @Transactional
    public void adminDeleteLeaveBalance(String tenantId, String userId, Integer year) {
        if (!StringUtils.hasText(userId) || year == null) {
            throw new BizException(HrErrorCode.LEAVE_BALANCE_NOT_FOUND);
        }
        HrLeaveBalanceId id = new HrLeaveBalanceId(blankToDefault(tenantId), userId.trim(), year);
        if (!leaveBalanceRepository.existsById(id)) {
            throw new BizException(HrErrorCode.LEAVE_BALANCE_NOT_FOUND);
        }
        leaveBalanceRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<AdminLeaveRequestVO> adminListLeaveRequests(String tenantId, String userId, String status) {
        String tenant = blankToDefault(tenantId);
        boolean hasUser = StringUtils.hasText(userId);
        boolean hasStatus = StringUtils.hasText(status) && !"all".equalsIgnoreCase(status.trim());
        List<HrLeaveRequestEntity> rows;
        if (hasUser && hasStatus) {
            rows = leaveRequestRepository.findByTenantIdAndUserIdAndStatusOrderByIdAsc(
                    tenant, userId.trim(), status.trim().toLowerCase(Locale.ROOT));
        } else if (hasUser) {
            rows = leaveRequestRepository.findByTenantIdAndUserIdOrderByIdAsc(tenant, userId.trim());
        } else if (hasStatus) {
            rows = leaveRequestRepository.findByTenantIdAndStatusOrderByIdAsc(
                    tenant, status.trim().toLowerCase(Locale.ROOT));
        } else {
            rows = leaveRequestRepository.findByTenantIdOrderByIdAsc(tenant);
        }
        return rows.stream().map(this::toAdminLeaveRequest).toList();
    }

    @Transactional
    public AdminLeaveRequestVO adminCreateLeaveRequest(AdminLeaveRequestRequest request) {
        requireLeaveRequestAdmin(request);
        Instant now = Instant.now();
        HrLeaveRequestEntity entity = new HrLeaveRequestEntity();
        entity.setId("leave-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        entity.setTenantId(blankToDefault(request.tenantId()));
        entity.setUserId(request.userId().trim());
        entity.setLeaveType(request.leaveType().trim().toLowerCase(Locale.ROOT));
        entity.setStartDate(LocalDate.parse(request.startDate().trim()));
        entity.setEndDate(LocalDate.parse(request.endDate().trim()));
        entity.setReason(StringUtils.hasText(request.reason()) ? request.reason().trim() : null);
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toLowerCase(Locale.ROOT) : "pending");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toAdminLeaveRequest(leaveRequestRepository.save(entity));
    }

    @Transactional
    public AdminLeaveRequestVO adminUpdateLeaveRequest(String id, AdminLeaveRequestRequest request) {
        if (!StringUtils.hasText(id)) {
            throw new BizException(HrErrorCode.LEAVE_REQUEST_NOT_FOUND);
        }
        requireLeaveRequestAdmin(request);
        HrLeaveRequestEntity entity = leaveRequestRepository.findById(id.trim())
                .orElseThrow(() -> new BizException(HrErrorCode.LEAVE_REQUEST_NOT_FOUND));
        entity.setTenantId(blankToDefault(request.tenantId()));
        entity.setUserId(request.userId().trim());
        entity.setLeaveType(request.leaveType().trim().toLowerCase(Locale.ROOT));
        entity.setStartDate(LocalDate.parse(request.startDate().trim()));
        entity.setEndDate(LocalDate.parse(request.endDate().trim()));
        entity.setReason(StringUtils.hasText(request.reason()) ? request.reason().trim() : null);
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toLowerCase(Locale.ROOT) : entity.getStatus());
        entity.setUpdatedAt(Instant.now());
        return toAdminLeaveRequest(leaveRequestRepository.save(entity));
    }

    @Transactional
    public void adminDeleteLeaveRequest(String id) {
        if (!StringUtils.hasText(id) || !leaveRequestRepository.existsById(id.trim())) {
            throw new BizException(HrErrorCode.LEAVE_REQUEST_NOT_FOUND);
        }
        leaveRequestRepository.deleteById(id.trim());
    }

    @Transactional(readOnly = true)
    public List<AdminAttendanceMonthVO> adminListAttendanceMonths(String tenantId, String userId) {
        String tenant = blankToDefault(tenantId);
        List<HrAttendanceMonthEntity> rows = StringUtils.hasText(userId)
                ? attendanceMonthRepository.findByTenantIdAndUserIdOrderByYearMonthAsc(tenant, userId.trim())
                : attendanceMonthRepository.findByTenantIdOrderByUserIdAscYearMonthAsc(tenant);
        return rows.stream().map(this::toAdminAttendance).toList();
    }

    @Transactional
    public AdminAttendanceMonthVO adminCreateAttendanceMonth(AdminAttendanceMonthRequest request) {
        requireAttendanceRequest(request);
        String tenant = blankToDefault(request.tenantId());
        String user = request.userId().trim();
        String ym = request.yearMonth().trim();
        HrAttendanceMonthId id = new HrAttendanceMonthId(tenant, user, ym);
        if (attendanceMonthRepository.existsById(id)) {
            throw new BizException(HrErrorCode.INVALID_ATTENDANCE);
        }
        Instant now = Instant.now();
        HrAttendanceMonthEntity entity = new HrAttendanceMonthEntity();
        entity.setTenantId(tenant);
        entity.setUserId(user);
        entity.setYearMonth(ym);
        entity.setLateCount(request.lateCount() != null ? request.lateCount() : 0);
        entity.setOvertimeHours(nullToZero(request.overtimeHours()));
        entity.setFrostLedgerSummary(request.frostLedgerSummary());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toAdminAttendance(attendanceMonthRepository.save(entity));
    }

    @Transactional
    public AdminAttendanceMonthVO adminUpdateAttendanceMonth(
            String userId, String yearMonth, AdminAttendanceMonthRequest request) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(yearMonth)) {
            throw new BizException(HrErrorCode.ATTENDANCE_NOT_FOUND);
        }
        requireAttendanceFields(request);
        String tenant = blankToDefault(request != null ? request.tenantId() : null);
        HrAttendanceMonthEntity entity = attendanceMonthRepository
                .findByTenantIdAndUserIdAndYearMonth(tenant, userId.trim(), yearMonth.trim())
                .orElseThrow(() -> new BizException(HrErrorCode.ATTENDANCE_NOT_FOUND));
        entity.setLateCount(request.lateCount() != null ? request.lateCount() : 0);
        entity.setOvertimeHours(nullToZero(request.overtimeHours()));
        entity.setFrostLedgerSummary(request.frostLedgerSummary());
        entity.setUpdatedAt(Instant.now());
        return toAdminAttendance(attendanceMonthRepository.save(entity));
    }

    @Transactional
    public void adminDeleteAttendanceMonth(String tenantId, String userId, String yearMonth) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(yearMonth)) {
            throw new BizException(HrErrorCode.ATTENDANCE_NOT_FOUND);
        }
        HrAttendanceMonthId id = new HrAttendanceMonthId(
                blankToDefault(tenantId), userId.trim(), yearMonth.trim());
        if (!attendanceMonthRepository.existsById(id)) {
            throw new BizException(HrErrorCode.ATTENDANCE_NOT_FOUND);
        }
        attendanceMonthRepository.deleteById(id);
    }

    private void requireLeaveBalanceRequest(AdminLeaveBalanceRequest request) {
        if (request == null || !StringUtils.hasText(request.userId()) || request.year() == null) {
            throw new BizException(HrErrorCode.INVALID_LEAVE_BALANCE);
        }
    }

    private void requireLeaveBalanceFields(AdminLeaveBalanceRequest request) {
        if (request == null) {
            throw new BizException(HrErrorCode.INVALID_LEAVE_BALANCE);
        }
    }

    private void requireLeaveRequestAdmin(AdminLeaveRequestRequest request) {
        if (request == null || !StringUtils.hasText(request.userId())
                || !StringUtils.hasText(request.leaveType())
                || !StringUtils.hasText(request.startDate())
                || !StringUtils.hasText(request.endDate())) {
            throw new BizException(HrErrorCode.INVALID_LEAVE_REQUEST);
        }
    }

    private void requireAttendanceRequest(AdminAttendanceMonthRequest request) {
        if (request == null || !StringUtils.hasText(request.userId())
                || !StringUtils.hasText(request.yearMonth())) {
            throw new BizException(HrErrorCode.INVALID_ATTENDANCE);
        }
    }

    private void requireAttendanceFields(AdminAttendanceMonthRequest request) {
        if (request == null) {
            throw new BizException(HrErrorCode.INVALID_ATTENDANCE);
        }
    }

    private AdminLeaveBalanceVO toAdminLeaveBalance(HrLeaveBalanceEntity e) {
        return new AdminLeaveBalanceVO(
                e.getTenantId(), e.getUserId(), e.getYear(),
                e.getAnnual(), e.getQingsong(), e.getCompensatory());
    }

    private AdminLeaveRequestVO toAdminLeaveRequest(HrLeaveRequestEntity e) {
        return new AdminLeaveRequestVO(
                e.getId(), e.getTenantId(), e.getUserId(), e.getLeaveType(),
                e.getStartDate().toString(), e.getEndDate().toString(),
                e.getReason(), e.getStatus());
    }

    private AdminAttendanceMonthVO toAdminAttendance(HrAttendanceMonthEntity e) {
        return new AdminAttendanceMonthVO(
                e.getTenantId(), e.getUserId(), e.getYearMonth(),
                e.getLateCount(), e.getOvertimeHours(), e.getFrostLedgerSummary());
    }

    private LeaveBalance toLeaveBalance(HrLeaveBalanceEntity e) {
        return new LeaveBalance(
                e.getYear(),
                e.getAnnual().intValue(),
                e.getQingsong().intValue(),
                e.getCompensatory().intValue());
    }

    private LeaveRequest toLeaveRequest(HrLeaveRequestEntity e) {
        return new LeaveRequest(
                e.getId(), e.getLeaveType(),
                e.getStartDate().toString(), e.getEndDate().toString(),
                e.getReason(), e.getStatus());
    }

    private AttendanceMonth toAttendanceMonth(HrAttendanceMonthEntity e) {
        return new AttendanceMonth(
                e.getYearMonth(),
                e.getLateCount(),
                e.getOvertimeHours().doubleValue(),
                e.getFrostLedgerSummary());
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String blankToDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }

    private static String blankToEmpty(String userId) {
        return userId == null ? "" : userId.trim();
    }
}
