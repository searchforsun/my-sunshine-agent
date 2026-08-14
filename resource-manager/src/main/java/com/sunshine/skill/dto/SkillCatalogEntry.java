package com.sunshine.skill.dto;

import com.sunshine.common.sandbox.SandboxPolicy;

import java.time.Instant;

/** Skill 目录详情 — 含正文 overlay 与工具绑定，供 runtime 按需加载 */
public record SkillCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemOverlay,
        String toolsJson,
        int version,
        boolean enabled,
        Instant activeVersionCreatedAt,
        String activeVersionMaintainer,
        boolean activeVersionPublished,
        String sandbox,
        SandboxPolicy sandboxPolicy,
        String kind,
        String bizScene
) {
}
