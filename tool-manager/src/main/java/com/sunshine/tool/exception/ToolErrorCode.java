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
    TOOL_DISABLED(403, "tool_disabled", "工具未启用"),
    UNSUPPORTED_SOURCE(400, "tool_unsupported_source", "暂不支持该工具来源"),
    SDK_APP_NOT_FOUND(404, "sdk_app_not_found", "SDK 应用不存在"),
    SDK_APP_OFFLINE(503, "sdk_app_offline", "SDK 应用无在线实例"),
    SDK_INVOKE_FAILED(502, "sdk_invoke_failed", "SDK 工具调用失败"),
    MCP_SERVER_NOT_FOUND(404, "mcp_server_not_found", "MCP 服务不存在"),
    MCP_SERVER_EXISTS(409, "mcp_server_exists", "MCP 服务 ID 已存在"),
    MCP_SERVER_ID_REQUIRED(400, "mcp_server_id_required", "MCP 服务 ID 不能为空"),
    MCP_SERVER_DISABLED(403, "mcp_server_disabled", "MCP 服务未启用"),
    MCP_PROBE_FAILED(502, "mcp_probe_failed", "MCP 服务探测失败"),
    MCP_INVOKE_FAILED(502, "mcp_invoke_failed", "MCP 工具调用失败"),
    TOOL_SET_NOT_FOUND(404, "tool_set_not_found", "工具集不存在"),
    EXECUTION_MODE_POLICY_NOT_FOUND(404, "execution_mode_policy_not_found", "执行模式策略不存在"),
    LOCAL_TOOL_INVOKE(400, "tool_local_invoke", "本地工具须由 orchestrator 执行，不可经 tool-manager invoke"),
    SUMMARIZE_INPUT_REQUIRED(400, "tool_summarize_input_required", "摘要请求不能为空"),
    TOOL_ID_INVALID(400, "tool_id_invalid", "工具 ID 不符合规范（仅允许字母数字_-，使用 __ 拼接）");

    private final int code;
    private final String key;
    private final String message;
}
