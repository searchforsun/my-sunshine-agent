package com.sunshine.hr.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** hr-biz-service 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum HrErrorCode implements ErrorCode {

    USER_REQUIRED(400, "hr_user_required", "缺少 x-user-id"),
    INVALID_LEAVE_REQUEST(400, "hr_invalid_leave", "请假提交参数不完整"),
    INVALID_LEAVE_BALANCE(400, "hr_invalid_leave_balance", "假期余额参数不完整"),
    INVALID_ATTENDANCE(400, "hr_invalid_attendance", "考勤月报参数不完整"),
    ATTENDANCE_NOT_FOUND(404, "hr_attendance_not_found", "考勤月报不存在"),
    LEAVE_BALANCE_NOT_FOUND(404, "hr_leave_balance_not_found", "假期余额不存在"),
    LEAVE_REQUEST_NOT_FOUND(404, "hr_leave_request_not_found", "请假单不存在");

    private final int code;
    private final String key;
    private final String message;
}
