package com.sunshine.sandbox.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SandboxErrorCode implements ErrorCode {

    SESSION_NOT_FOUND(404, "sandbox_session_not_found", "会话不存在"),
    NETWORK_ALLOW_NOT_SUPPORTED(400, "sandbox_network_allow_not_supported", "网络白名单将在 T7 启用"),
    SKILL_FILE_PATH_INVALID(400, "sandbox_skill_file_path_invalid", "skill 文件路径必须在 scripts/ 或 references/ 下"),
    FILE_PATH_INVALID(400, "sandbox_file_path_invalid", "文件路径无效");

    private final int code;
    private final String key;
    private final String message;
}
