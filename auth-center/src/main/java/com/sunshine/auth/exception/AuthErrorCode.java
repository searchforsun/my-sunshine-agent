package com.sunshine.auth.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** auth-center 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    LOGIN_FAILED(401, "auth_login_failed", "用户名或密码不正确"),
    TOKEN_INVALID(401, "auth_token_invalid", "未登录或 Token 已失效"),
    USER_NOT_FOUND(401, "auth_user_not_found", "用户不存在或 Token 已失效"),
    USER_DISABLED(403, "auth_user_disabled", "账号已停用，请联系管理员"),
    USERNAME_TAKEN(409, "auth_username_taken", "用户名已被占用"),
    VALIDATION_FAILED(400, "auth_validation_failed", "请检查填写的内容");

    private final int code;
    private final String key;
    private final String message;
}
