package com.sunshine.orchestrator.catalog;

import com.sunshine.common.sandbox.SandboxPolicy;

import java.util.List;

/** Skill 目录详情 — 含正文 overlay 与工具绑定 */
public record SkillCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemOverlay,
        String toolsJson,
        int version,
        boolean enabled,
        String sandbox,
        SandboxPolicy sandboxPolicy,
        String kind,
        String bizScene,
        String tenantId
) {
    public SkillCatalogEntry {
        if (kind == null || kind.isBlank()) {
            kind = "all";
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
    }

    /** 解析 toolsJson 为 Catalog ID 列表（无效 JSON → 空） */
    public List<String> toolIds() {
        return AgentToolsJson.parse(toolsJson);
    }
}
