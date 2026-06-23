package com.sunshine.skill.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogIndexEntryTest {

    @Test
    void from_omitsSystemOverlay() {
        SkillCatalogEntry full = new SkillCatalogEntry(
                "finance-analysis", "财务合规分析", "desc", "secret overlay body", 1, true, null, null, true);
        SkillCatalogIndexEntry index = SkillCatalogIndexEntry.from(full);
        assertThat(index.id()).isEqualTo("finance-analysis");
        assertThat(index.displayName()).isEqualTo("财务合规分析");
        assertThat(index.description()).isEqualTo("desc");
        assertThat(index.version()).isEqualTo(1);
    }
}
