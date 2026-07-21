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
    ATTENDANCE_NOT_FOUND(404, "hr_attendance_not_found", "考勤月报不存在");

    private final int code;
    private final String key;
    private final String message;
}
