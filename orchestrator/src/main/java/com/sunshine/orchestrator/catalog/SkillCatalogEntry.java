package com.sunshine.orchestrator.catalog;

import com.sunshine.common.sandbox.SandboxPolicy;

/** Skill 目录详情 — 含正文 overlay */
public record SkillCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemOverlay,
        int version,
        boolean enabled,
        String sandbox,
        SandboxPolicy sandboxPolicy
) {
}
