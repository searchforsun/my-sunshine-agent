package com.sunshine.skill.dto;

import java.time.Instant;

/** Skill 目录详情 — 含正文 overlay，供 runtime 按需加载 */
public record SkillCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemOverlay,
        int version,
        boolean enabled,
        Instant activeVersionCreatedAt,
        String activeVersionMaintainer,
        boolean activeVersionPublished
) {
}
