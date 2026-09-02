package com.sunshine.common.skill;

/** Redis pub/sub：Skill Catalog 变更通知（resource-manager → orchestrator）。 */
public final class SkillCatalogChannels {

    public static final String CHANGED = "skill-catalog-changed";

    private SkillCatalogChannels() {
    }
}
