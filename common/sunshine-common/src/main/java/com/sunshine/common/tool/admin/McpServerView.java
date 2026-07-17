package com.sunshine.common.tool.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** MCP 服务 Admin API 视图 — tool-manager / BFF 共用 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerView(
        String id,
        String displayName,
        String transport,
        String command,
        String argsJson,
        String endpoint,
        String envJson,
        String tenantId,
        boolean enabled,
        Instant lastProbeAt,
        String probeStatus,
        String probeError,
        Instant createdAt,
        Instant updatedAt) {
}
