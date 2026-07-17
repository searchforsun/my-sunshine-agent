package com.sunshine.skill.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillFileServiceMaterialTest {

    @Test
    void isMaterialTextPath_allowsSkillMdScriptsAndReferencesText() {
        assertThat(SkillFileService.isMaterialTextPath("SKILL.md")).isTrue();
        assertThat(SkillFileService.isMaterialTextPath("skill.md")).isTrue();
        assertThat(SkillFileService.isMaterialTextPath("scripts/run.py")).isTrue();
        assertThat(SkillFileService.isMaterialTextPath("references/guide.md")).isTrue();
        assertThat(SkillFileService.isMaterialTextPath("docs/SKILL.md")).isFalse();
        assertThat(SkillFileService.isMaterialTextPath("scripts/bin.dat")).isFalse();
        assertThat(SkillFileService.isMaterialTextPath("scripts/")).isFalse();
        assertThat(SkillFileService.isMaterialTextPath("other/scripts/a.py")).isFalse();
    }
}
