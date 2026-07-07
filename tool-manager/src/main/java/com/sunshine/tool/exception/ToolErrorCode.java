package com.sunshine.tool.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** tool-manager 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum ToolErrorCode implements ErrorCode {

    TOOL_NAME_REQUIRED(400, "tool_name_required", "工具名称不能为空"),
    UNKNOWN_TOOL(404, "tool_unknown", "未知工具"),
    LOCAL_TOOL_INVOKE(400, "tool_local_invoke", "本地工具须由 orchestrator 执行，不可经 tool-manager invoke"),
    SUMMARIZE_INPUT_REQUIRED(400, "tool_summarize_input_required", "摘要请求不能为空");

    private final int code;
    private final String key;
    private final String message;
}
