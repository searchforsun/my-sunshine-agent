package com.sunshine.skill.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillFileServiceMaterialTest {

    @Test
    void isMaterialTextPath_allowsScriptsAndReferencesTextOnly() {
        assertThat(SkillFileService.isMaterialTextPath("scripts/run.py")).isTrue();
        assertThat(SkillFileService.isMaterialTextPath("references/guide.md")).isTrue();
        assertThat(SkillFileService.isMaterialTextPath("SKILL.md")).isFalse();
        assertThat(SkillFileService.isMaterialTextPath("scripts/bin.dat")).isFalse();
        assertThat(SkillFileService.isMaterialTextPath("scripts/")).isFalse();
        assertThat(SkillFileService.isMaterialTextPath("other/scripts/a.py")).isFalse();
    }
}
