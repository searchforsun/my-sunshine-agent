package com.sunshine.orchestrator.catalog;

/** Skill 目录摘要 — 意图识别 / @ 补全 / Planner 选题，不含正文 */
public record SkillCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        int version,
        boolean enabled
) {
}
