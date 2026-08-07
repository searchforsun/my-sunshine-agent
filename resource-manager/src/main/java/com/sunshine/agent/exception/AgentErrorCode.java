package com.sunshine.agent.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AgentErrorCode implements ErrorCode {
    ID_DISPLAY_NAME_REQUIRED(400, "agent_id_display_name_required", "智能体 ID 与展示名不能为空"),
    AGENT_ALREADY_EXISTS(409, "agent_already_exists", "智能体 ID 已存在"),
    AGENT_NOT_FOUND(404, "agent_not_found", "未找到智能体"),
    DISPLAY_NAME_REQUIRED(400, "agent_display_name_required", "展示名不能为空"),
    SYSTEM_PROMPT_REQUIRED(400, "agent_system_prompt_required", "系统提示词不能为空");

    private final int code;
    private final String key;
    private final String message;
}
