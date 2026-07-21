package com.sunshine.hr.tools;

import com.sunshine.hr.model.LeaveBalance;
import com.sunshine.hr.model.LeaveRequest;
import com.sunshine.hr.store.HrTenantUserStore;
import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;
import com.sunshine.tools.sdk.context.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class HrSunshineTools {

    private final HrTenantUserStore store;

    @SunshineTool(
            id = "get_leave_balance",
            displayName = "查询假期余额",
            description = "查询当前用户年假/青松假/调休余额。用户问青松假还有几天、年假余额时调用。year 可选，默认当年。",
            timelineSummaryTemplate = "青松假 {qingsong} 天",
            timelineSummaryExtract = "{\"qingsong\":\"regex:青松假=(\\\\d+)\"}")
    public String getLeaveBalance(
            @ToolParam(value = "year", description = "年份，如 2026", required = false) String year) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        Integer parsedYear = parseYear(year);
        Optional<LeaveBalance> balance = store.getLeaveBalance(tenantId, userId, parsedYear);
        if (balance.isEmpty()) {
            return "未查询到该年度假期余额。";
        }
        LeaveBalance b = balance.get();
        return """
                %d 年假期余额：
                - 年假=%d
                - 青松假=%d
                - 调休=%d
                """.formatted(b.year(), b.annual(), b.qingsong(), b.compensatory()).strip();
    }

    @SunshineTool(
            id = "list_leave_requests",
            displayName = "查询我的请假单",
            description = "查询当前用户的请假申请。status: pending|approved|all",
            timelineSummaryTemplate = "{count} 条请假单",
            timelineSummaryExtract = "{\"count\":\"regex:共\\\\s*(\\\\d+)\\\\s*条\"}")
    public String listLeaveRequests(
            @ToolParam(value = "status", description = "pending | approved | all", required = false) String status) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        List<LeaveRequest> requests = store.listLeaveRequests(tenantId, userId, status != null ? status : "all");
        if (requests.isEmpty()) {
            return "未查询到符合条件的请假单。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(requests.size()).append(" 条请假单：\n");
        for (LeaveRequest r : requests) {
            sb.append("- [").append(r.id()).append("] ")
                    .append(leaveTypeLabel(r.leaveType()))
                    .append(" | 状态=").append(r.status())
                    .append(" | ").append(r.startDate()).append(" ~ ").append(r.endDate());
            if (StringUtils.hasText(r.reason())) {
                sb.append(" | 事由=").append(r.reason());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    @SunshineTool(
            id = "submit_leave_request",
            displayName = "提交请假申请",
            description = "为当前用户提交请假申请（pending）。leaveType: annual|qingsong|compensatory",
            sideEffect = "write",
            timelineSummaryTemplate = "已提交 {leaveId}",
            timelineSummaryExtract = "{\"leaveId\":\"regex:id=(\\\\S+)\"}")
    public String submitLeaveRequest(
            @ToolParam(value = "leaveType", description = "annual | qingsong | compensatory") String leaveType,
            @ToolParam(value = "startDate", description = "开始日 YYYY-MM-DD") String startDate,
            @ToolParam(value = "endDate", description = "结束日 YYYY-MM-DD") String endDate,
            @ToolParam(value = "reason", description = "请假事由") String reason) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        if (!StringUtils.hasText(leaveType) || !StringUtils.hasText(startDate)
                || !StringUtils.hasText(endDate) || !StringUtils.hasText(reason)) {
            return "请提供 leaveType、startDate、endDate、reason。";
        }
        LeaveRequest created = store.submitLeaveRequest(
                tenantId, userId, leaveType.trim(), startDate.trim(), endDate.trim(), reason.trim());
        return "已提交请假单：id=" + created.id()
                + " | 类型=" + leaveTypeLabel(created.leaveType())
                + " | " + created.startDate() + " ~ " + created.endDate()
                + " | 状态=" + created.status();
    }

    @SunshineTool(
            id = "get_attendance_month",
            displayName = "查询月度考勤",
            description = "按 yearMonth（YYYY-MM）查询当前用户迟到次数、加班时长与霜降台账摘要。",
            timelineSummaryTemplate = "迟到 {late} 次",
            timelineSummaryExtract = "{\"late\":\"regex:迟到=(\\\\d+)\"}")
    public String getAttendanceMonth(
            @ToolParam(value = "yearMonth", description = "月份 YYYY-MM，如 2026-07") String yearMonth) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        if (!StringUtils.hasText(yearMonth)) {
            return "请提供 yearMonth（YYYY-MM）。";
        }
        return store.getAttendanceMonth(tenantId, userId, yearMonth.trim())
                .map(a -> """
                        %s 考勤摘要：
                        - 迟到=%d 次
                        - 加班=%.1f 小时
                        - 霜降台账=%s
                        """.formatted(
                        a.yearMonth(), a.lateCount(), a.overtimeHours(),
                        a.frostLedgerSummary() != null ? a.frostLedgerSummary() : "").strip())
                .orElse("未找到 yearMonth=" + yearMonth + " 的考勤数据。");
    }

    private static Integer parseYear(String year) {
        if (!StringUtils.hasText(year)) {
            return null;
        }
        try {
            return Integer.parseInt(year.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String leaveTypeLabel(String leaveType) {
        if (leaveType == null) {
            return "";
        }
        return switch (leaveType) {
            case "annual" -> "年假";
            case "qingsong" -> "青松假";
            case "compensatory" -> "调休";
            default -> leaveType;
        };
    }
}
