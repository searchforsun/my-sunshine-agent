package com.sunshine.sandbox.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SandboxErrorCode implements ErrorCode {

    SESSION_NOT_FOUND(404, "sandbox_session_not_found", "会话不存在"),
    SKILL_FILE_PATH_INVALID(400, "sandbox_skill_file_path_invalid", "skill 文件路径必须在 scripts/ 或 references/ 下"),
    FILE_PATH_INVALID(400, "sandbox_file_path_invalid", "文件路径无效"),
    IMAGE_INVALID(400, "sandbox_image_invalid", "镜像引用无效"),
    TOOL_UNKNOWN(400, "sandbox_tool_unknown", "未知工具"),
    TOOL_NOT_IMPLEMENTED(400, "sandbox_tool_not_implemented", "工具尚未实现"),
    EDIT_NOT_UNIQUE(400, "sandbox_edit_not_unique", "old_string not unique"),
    EDIT_NOT_FOUND(400, "sandbox_edit_not_found", "old_string not found"),
    PATTERN_INVALID(400, "sandbox_pattern_invalid", "正则无效");

    private final int code;
    private final String key;
    private final String message;
}
