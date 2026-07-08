package com.sunshine.expert.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ExpertErrorCode implements ErrorCode {
    ID_DISPLAY_NAME_REQUIRED(400, "expert_id_display_name_required", "专家 ID 与展示名不能为空"),
    EXPERT_ALREADY_EXISTS(409, "expert_already_exists", "专家 ID 已存在"),
    EXPERT_NOT_FOUND(404, "expert_not_found", "未找到专家"),
    DISPLAY_NAME_REQUIRED(400, "expert_display_name_required", "展示名不能为空"),
    SYSTEM_PROMPT_REQUIRED(400, "expert_system_prompt_required", "系统提示词不能为空");

    private final int code;
    private final String key;
    private final String message;
}
