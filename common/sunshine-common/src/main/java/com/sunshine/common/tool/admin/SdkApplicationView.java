package com.sunshine.common.tool.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** SDK 应用 Admin API 视图 — tool-manager / BFF 共用 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SdkApplicationView(
        String id,
        String nacosService,
        String displayName,
        String catalogPath,
        String invokePath,
        String tenantId,
        String status,
        Instant lastSeenAt,
        int schemaVersion,
        Instant createdAt,
        Instant updatedAt) {
}
