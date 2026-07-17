package com.sunshine.skill.dto;

/** Skill 目录摘要 — 动态披露 L0 / L3 意图，不含 systemOverlay */
public record SkillCatalogIndexEntry(
        String id,
        String displayName,
        String description,
        int version,
        boolean enabled,
        String sandbox
) {
    public SkillCatalogIndexEntry {
        if (sandbox == null || sandbox.isBlank()) {
            sandbox = "none";
        }
    }

    public static SkillCatalogIndexEntry from(SkillCatalogEntry full) {
        return new SkillCatalogIndexEntry(
                full.id(),
                full.displayName(),
                full.description(),
                full.version(),
                full.enabled(),
                full.sandbox());
    }
}
