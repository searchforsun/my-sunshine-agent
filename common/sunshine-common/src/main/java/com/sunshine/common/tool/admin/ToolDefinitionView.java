package com.sunshine.common.tool.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/** 工具定义 Admin API 视图 — tool-manager / BFF 共用 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolDefinitionView(
        String id,
        String source,
        String sourceRef,
        String externalName,
        String displayName,
        String description,
        Map<String, Object> schemaJson,
        String schemaHash,
        String kind,
        String timelineSummaryTemplate,
        String timelineSummaryExtract,
        String sideEffect,
        boolean requireConfirmation,
        boolean confirmationEdited,
        String tenantId,
        boolean enabled,
        boolean metadataEdited,
        boolean idValid,
        String idError,
        Instant discoveredAt,
        Instant updatedAt) {
}
