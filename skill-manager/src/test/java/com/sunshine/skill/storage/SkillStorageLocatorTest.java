package com.sunshine.skill.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillStorageLocatorTest {

    @Test
    void minioLocator_roundTrip() {
        String locator = SkillStorageLocator.minio("sunshine-skills", "compliance-check/2/SKILL.md");
        assertThat(SkillStorageLocator.isMinio(locator)).isTrue();
        SkillStorageLocator.MinioRef ref = SkillStorageLocator.parseMinio(locator);
        assertThat(ref.bucket()).isEqualTo("sunshine-skills");
        assertThat(ref.objectKey()).isEqualTo("compliance-check/2/SKILL.md");
    }

    @Test
    void versionPrefix_and_skillMdKey() {
        assertThat(SkillStorageLocator.versionPrefix("finance-analysis", 3))
                .isEqualTo("finance-analysis/3/");
        assertThat(SkillStorageLocator.skillMdKey("finance-analysis", 3))
                .isEqualTo("finance-analysis/3/SKILL.md");
    }

    @Test
    void parseMinio_rejectsInvalid() {
        assertThatThrownBy(() -> SkillStorageLocator.parseMinio("data/skills/a/SKILL.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
