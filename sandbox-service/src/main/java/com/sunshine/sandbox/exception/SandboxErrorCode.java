package com.sunshine.sandbox.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SandboxErrorCode implements ErrorCode {

    SESSION_NOT_FOUND(404, "sandbox_session_not_found", "会话不存在"),
    FILE_NOT_FOUND(404, "sandbox_file_not_found", "文件不存在"),
    SKILL_FILE_PATH_INVALID(400, "sandbox_skill_file_path_invalid", "skill 文件路径无效"),
    SKILL_ID_INVALID(400, "sandbox_skill_id_invalid", "skillId 无效"),
    FILE_PATH_INVALID(400, "sandbox_file_path_invalid", "文件路径无效"),
    IMAGE_INVALID(400, "sandbox_image_invalid", "镜像引用无效"),
    TOOL_UNKNOWN(400, "sandbox_tool_unknown", "未知工具"),
    TOOL_NOT_IMPLEMENTED(400, "sandbox_tool_not_implemented", "工具尚未实现"),
    EDIT_NOT_UNIQUE(400, "sandbox_edit_not_unique", "old_string not unique"),
    EDIT_NOT_FOUND(400, "sandbox_edit_not_found", "old_string not found"),
    PATTERN_INVALID(400, "sandbox_pattern_invalid", "正则无效"),
    WRITE_ALREADY_EXISTS(409, "sandbox_write_already_exists", "file already exists"),
    EXEC_BLOCKED(403, "sandbox_exec_blocked", "command blocked"),
    NOT_A_FILE(400, "sandbox_not_a_file", "path is not a regular file");

    private final int code;
    private final String key;
    private final String message;
}
