package com.sunshine.tool.admin.dto;

/** MCP 服务部分更新 */
public record McpServerPatchRequest(
        String displayName,
        String transport,
        String command,
        String argsJson,
        String endpoint,
        String envJson,
        Boolean enabled
) {
}
